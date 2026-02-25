package com.whatisseimo.doing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveServiceSocketStatusTest {
    @Test
    fun `mapSocketEventToNotificationState maps known events`() {
        assertEquals(
            SocketNotificationState.CONNECTED,
            mapSocketEventToNotificationState(DeviceSocketClient.EVENT_CONNECT),
        )
        assertEquals(
            SocketNotificationState.RECONNECTING,
            mapSocketEventToNotificationState(DeviceSocketClient.EVENT_RECONNECT_ATTEMPT),
        )
        assertEquals(
            SocketNotificationState.RECONNECTING,
            mapSocketEventToNotificationState(DeviceSocketClient.EVENT_DISCONNECT),
        )
        assertEquals(
            SocketNotificationState.RECONNECTING,
            mapSocketEventToNotificationState(DeviceSocketClient.EVENT_CONNECT_ERROR),
        )
        assertEquals(
            SocketNotificationState.DISCONNECTED,
            mapSocketEventToNotificationState(DeviceSocketClient.EVENT_RECONNECT_FAILED),
        )
        assertNull(mapSocketEventToNotificationState("UNKNOWN_EVENT"))
    }

    @Test
    fun `shouldTriggerImmediateReconnect only for disconnect and errors`() {
        assertTrue(shouldTriggerImmediateReconnect(DeviceSocketClient.EVENT_DISCONNECT))
        assertTrue(shouldTriggerImmediateReconnect(DeviceSocketClient.EVENT_CONNECT_ERROR))
        assertTrue(shouldTriggerImmediateReconnect(DeviceSocketClient.EVENT_RECONNECT_FAILED))
        assertFalse(shouldTriggerImmediateReconnect(DeviceSocketClient.EVENT_CONNECT))
        assertFalse(shouldTriggerImmediateReconnect(DeviceSocketClient.EVENT_RECONNECT_ATTEMPT))
    }

    @Test
    fun `hasSocketCredentials requires both wsUrl and token`() {
        assertTrue(hasSocketCredentials("ws://example.com/ws", "token"))
        assertFalse(hasSocketCredentials(null, "token"))
        assertFalse(hasSocketCredentials("ws://example.com/ws", null))
        assertFalse(hasSocketCredentials("", "token"))
        assertFalse(hasSocketCredentials("ws://example.com/ws", ""))
    }

    @Test
    fun `shouldRunImmediateReconnect honors cooldown window`() {
        val minGapMs = 3_000L
        val firstAttemptAt = 10_000L

        assertTrue(shouldRunImmediateReconnect(lastAttemptAtMs = 0L, nowMs = firstAttemptAt, minGapMs = minGapMs))
        assertFalse(
            shouldRunImmediateReconnect(
                lastAttemptAtMs = firstAttemptAt,
                nowMs = firstAttemptAt + 2_000L,
                minGapMs = minGapMs,
            ),
        )
        assertTrue(
            shouldRunImmediateReconnect(
                lastAttemptAtMs = firstAttemptAt,
                nowMs = firstAttemptAt + 3_000L,
                minGapMs = minGapMs,
            ),
        )
    }
}
