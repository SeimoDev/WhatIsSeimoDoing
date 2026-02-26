package com.whatisseimo.doing.data

import com.whatisseimo.doing.model.DailySnapshotRequest
import com.whatisseimo.doing.model.ForegroundSwitchRequest
import com.whatisseimo.doing.model.QueuePayload
import com.whatisseimo.doing.model.QueuedEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventQueueStorePolicyTest {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    @Test
    fun `enqueue policy trims oldest entries when queue exceeds max event count`() {
        val existing = (1..EVENT_QUEUE_MAX_EVENTS).map { index ->
            foregroundEvent(
                id = "fg-$index",
                createdAt = index.toLong(),
            )
        }
        val incoming = foregroundEvent(
            id = "fg-new",
            createdAt = (EVENT_QUEUE_MAX_EVENTS + 1).toLong(),
        )

        val result = applyQueueEnqueuePolicy(
            existingEvents = existing,
            incoming = incoming,
            json = json,
        )

        assertTrue(result.accepted)
        assertEquals(EVENT_QUEUE_MAX_EVENTS, result.events.size)
        assertTrue(result.events.any { it.id == "fg-new" })
        assertFalse(result.events.any { it.id == "fg-1" })
        assertTrue(result.droppedCount >= 1)
    }

    @Test
    fun `enqueue policy keeps only latest snapshot event`() {
        val existing = listOf(
            foregroundEvent(id = "fg-1", createdAt = 1L),
            snapshotEvent(id = "snapshot-1", createdAt = 2L),
            foregroundEvent(id = "fg-2", createdAt = 3L),
        )
        val incoming = snapshotEvent(id = "snapshot-2", createdAt = 4L)

        val result = applyQueueEnqueuePolicy(
            existingEvents = existing,
            incoming = incoming,
            json = json,
        )

        assertTrue(result.accepted)
        val snapshots = result.events.filter { event -> event.payload is QueuePayload.Snapshot }
        assertEquals(1, snapshots.size)
        assertEquals("snapshot-2", snapshots.first().id)
    }

    @Test
    fun `enqueue policy rejects oversized single event`() {
        val incoming = foregroundEvent(
            id = "fg-too-large",
            createdAt = 10L,
            iconBase64 = "a".repeat(EVENT_QUEUE_MAX_SINGLE_EVENT_BYTES * 2),
        )

        val result = applyQueueEnqueuePolicy(
            existingEvents = emptyList(),
            incoming = incoming,
            json = json,
        )

        assertFalse(result.accepted)
        assertEquals(QueueRejectReason.EVENT_TOO_LARGE, result.rejectReason)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `peek and ack helpers keep ordering semantics`() {
        val existing = (1..5).map { index ->
            foregroundEvent(
                id = "fg-$index",
                createdAt = index.toLong(),
            )
        }

        val peeked = peekQueuedEvents(existing, limit = 3)
        assertEquals(listOf("fg-1", "fg-2", "fg-3"), peeked.map { it.id })

        val remaining = ackQueuedEvents(existing, count = 3)
        assertEquals(listOf("fg-4", "fg-5"), remaining.map { it.id })
    }

    private fun foregroundEvent(
        id: String,
        createdAt: Long,
        iconBase64: String? = null,
    ): QueuedEvent {
        return QueuedEvent(
            id = id,
            createdAt = createdAt,
            payload = QueuePayload.Foreground(
                ForegroundSwitchRequest(
                    ts = createdAt,
                    packageName = "com.example.$id",
                    appName = id,
                    iconHash = null,
                    iconBase64 = iconBase64,
                    todayUsageMsAtSwitch = 1_000L,
                ),
            ),
        )
    }

    private fun snapshotEvent(id: String, createdAt: Long): QueuedEvent {
        return QueuedEvent(
            id = id,
            createdAt = createdAt,
            payload = QueuePayload.Snapshot(
                DailySnapshotRequest(
                    ts = createdAt,
                    totalNotificationCount = 1,
                    unlockCount = 1,
                    apps = emptyList(),
                ),
            ),
        )
    }
}
