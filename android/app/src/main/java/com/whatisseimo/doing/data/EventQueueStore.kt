package com.whatisseimo.doing.data

import android.content.Context
import android.content.SharedPreferences
import com.whatisseimo.doing.model.QueuedEvent
import com.whatisseimo.doing.model.QueuePayload
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal const val EVENT_QUEUE_MAX_EVENTS = 300
internal const val EVENT_QUEUE_MAX_TOTAL_BYTES = 786_432
internal const val EVENT_QUEUE_MAX_SINGLE_EVENT_BYTES = 98_304

enum class QueueRejectReason {
    EVENT_TOO_LARGE,
    DROPPED_BY_QUEUE_LIMIT,
}

data class QueueEnqueueResult(
    val accepted: Boolean,
    val droppedCount: Int,
    val queueSize: Int,
    val queueBytes: Int,
    val rejectReason: QueueRejectReason? = null,
)

internal data class QueueMutationResult(
    val events: List<QueuedEvent>,
    val accepted: Boolean,
    val droppedCount: Int,
    val queueBytes: Int,
    val rejectReason: QueueRejectReason? = null,
)

internal fun normalizeQueueEvents(events: List<QueuedEvent>): MutableList<QueuedEvent> {
    return events
        .sortedWith(
            compareBy<QueuedEvent> { it.createdAt }
                .thenBy { it.id },
        )
        .toMutableList()
}

internal fun queueBytes(events: List<QueuedEvent>, json: Json): Int {
    if (events.isEmpty()) {
        return 2 // "[]"
    }
    val raw = runCatching {
        json.encodeToString(ListSerializer(QueuedEvent.serializer()), events)
    }.getOrElse {
        return Int.MAX_VALUE
    }
    return raw.toByteArray(Charsets.UTF_8).size
}

internal fun eventBytes(event: QueuedEvent, json: Json): Int {
    val raw = runCatching {
        json.encodeToString(QueuedEvent.serializer(), event)
    }.getOrElse {
        return Int.MAX_VALUE
    }
    return raw.toByteArray(Charsets.UTF_8).size
}

internal fun trimQueueByLimits(
    events: MutableList<QueuedEvent>,
    json: Json,
    maxEvents: Int,
    maxTotalBytes: Int,
): Pair<Int, Int> {
    var dropped = 0
    while (events.size > maxEvents && events.isNotEmpty()) {
        events.removeAt(0)
        dropped += 1
    }

    var currentBytes = queueBytes(events, json)
    while (events.isNotEmpty() && currentBytes > maxTotalBytes) {
        events.removeAt(0)
        dropped += 1
        currentBytes = queueBytes(events, json)
    }

    return dropped to currentBytes
}

internal fun applyQueueEnqueuePolicy(
    existingEvents: List<QueuedEvent>,
    incoming: QueuedEvent,
    json: Json,
    maxEvents: Int = EVENT_QUEUE_MAX_EVENTS,
    maxTotalBytes: Int = EVENT_QUEUE_MAX_TOTAL_BYTES,
    maxSingleEventBytes: Int = EVENT_QUEUE_MAX_SINGLE_EVENT_BYTES,
): QueueMutationResult {
    val working = normalizeQueueEvents(existingEvents)
    var droppedCount = 0

    val (preTrimDropped, _) = trimQueueByLimits(
        events = working,
        json = json,
        maxEvents = maxEvents,
        maxTotalBytes = maxTotalBytes,
    )
    droppedCount += preTrimDropped

    if (incoming.payload is QueuePayload.Snapshot) {
        val before = working.size
        working.removeAll { queued -> queued.payload is QueuePayload.Snapshot }
        droppedCount += before - working.size
    }

    val incomingBytes = eventBytes(incoming, json)
    if (incomingBytes > maxSingleEventBytes) {
        val bytes = queueBytes(working, json)
        return QueueMutationResult(
            events = working,
            accepted = false,
            droppedCount = droppedCount,
            queueBytes = bytes,
            rejectReason = QueueRejectReason.EVENT_TOO_LARGE,
        )
    }

    working.add(incoming)
    working.sortWith(
        compareBy<QueuedEvent> { it.createdAt }
            .thenBy { it.id },
    )

    val (postTrimDropped, postTrimBytes) = trimQueueByLimits(
        events = working,
        json = json,
        maxEvents = maxEvents,
        maxTotalBytes = maxTotalBytes,
    )
    droppedCount += postTrimDropped

    val accepted = working.any { queued -> queued.id == incoming.id }
    val rejectReason = if (accepted) {
        null
    } else {
        QueueRejectReason.DROPPED_BY_QUEUE_LIMIT
    }

    return QueueMutationResult(
        events = working,
        accepted = accepted,
        droppedCount = droppedCount,
        queueBytes = postTrimBytes,
        rejectReason = rejectReason,
    )
}

internal fun peekQueuedEvents(events: List<QueuedEvent>, limit: Int): List<QueuedEvent> {
    if (limit <= 0 || events.isEmpty()) {
        return emptyList()
    }
    return events.take(limit)
}

internal fun ackQueuedEvents(events: List<QueuedEvent>, count: Int): List<QueuedEvent> {
    if (count <= 0 || events.isEmpty()) {
        return events
    }
    return events.drop(count)
}

class EventQueueStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("event_queue", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    @Synchronized
    fun enqueue(event: QueuedEvent): QueueEnqueueResult {
        val current = readAll()
        val mutation = applyQueueEnqueuePolicy(
            existingEvents = current,
            incoming = event,
            json = json,
        )
        writeAll(mutation.events)
        return QueueEnqueueResult(
            accepted = mutation.accepted,
            droppedCount = mutation.droppedCount,
            queueSize = mutation.events.size,
            queueBytes = mutation.queueBytes,
            rejectReason = mutation.rejectReason,
        )
    }

    @Synchronized
    fun peekBatch(limit: Int): List<QueuedEvent> {
        val current = normalizeQueueEvents(readAll())
        return peekQueuedEvents(
            events = current,
            limit = limit,
        )
    }

    @Synchronized
    fun ackBatch(count: Int): Int {
        if (count <= 0) {
            return 0
        }

        val current = normalizeQueueEvents(readAll())
        if (current.isEmpty()) {
            return 0
        }

        val remaining = ackQueuedEvents(
            events = current,
            count = count,
        )
        val removed = current.size - remaining.size
        if (removed > 0) {
            writeAll(remaining)
        }
        return removed
    }

    @Synchronized
    fun size(): Int = readAll().size

    private fun readAll(): List<QueuedEvent> {
        val raw = prefs.getString("payload", null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(QueuedEvent.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeAll(events: List<QueuedEvent>) {
        val normalized = normalizeQueueEvents(events)
        val raw = json.encodeToString(ListSerializer(QueuedEvent.serializer()), normalized)
        prefs.edit().putString("payload", raw).apply()
    }
}
