package com.whatisseimo.doing.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketConnectionStateTest {
    @Test
    fun `disconnect marks state as reconnecting when reconnect is expected`() {
        val state = SocketConnectionState()

        state.onConnectRequested()
        state.onConnected()
        state.onDisconnected(willReconnect = true)

        assertFalse(state.isConnected)
        assertTrue(state.isConnecting)
    }

    @Test
    fun `reconnect failed clears connecting flag`() {
        val state = SocketConnectionState()

        state.onConnectRequested()
        state.onConnectError()
        state.onReconnectAttempt()
        state.onReconnectFailed()

        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
    }
}
