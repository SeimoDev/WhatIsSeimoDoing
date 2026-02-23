package com.whatisseimo.doing.data

import android.content.Context
import android.content.SharedPreferences
import com.whatisseimo.doing.model.QueuedEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class EventQueueStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("event_queue", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    @Synchronized
    fun enqueue(event: QueuedEvent) {
        val list = readAll().toMutableList()
        list.add(event)
        writeAll(list)
    }

    @Synchronized
    fun popBatch(limit: Int): List<QueuedEvent> {
        val list = readAll().toMutableList()
        if (list.isEmpty()) {
            return emptyList()
        }

        val batch = list.take(limit)
        val remaining = list.drop(limit)
        writeAll(remaining)
        return batch
    }

    @Synchronized
    fun prepend(events: List<QueuedEvent>) {
        if (events.isEmpty()) {
            return
        }

        val list = readAll().toMutableList()
        val merged = events + list
        writeAll(merged)
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
        val raw = json.encodeToString(ListSerializer(QueuedEvent.serializer()), events)
        prefs.edit().putString("payload", raw).apply()
    }
}
