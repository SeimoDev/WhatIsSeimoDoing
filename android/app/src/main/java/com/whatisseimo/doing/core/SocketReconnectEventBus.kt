package com.whatisseimo.doing.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SocketReconnectEvent(
    val sessionId: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String,
    val isTerminal: Boolean = false,
    val isSuccess: Boolean? = null,
)

object SocketReconnectEventBus {
    private val _events = MutableSharedFlow<SocketReconnectEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SocketReconnectEvent> = _events.asSharedFlow()

    fun emit(event: SocketReconnectEvent) {
        _events.tryEmit(event)
    }

    private const val EVENT_BUFFER_SIZE = 128
}
