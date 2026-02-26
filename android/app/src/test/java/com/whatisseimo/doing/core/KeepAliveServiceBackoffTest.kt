package com.whatisseimo.doing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAliveServiceBackoffTest {
    @Test
    fun `nextExponentialBackoffMs returns initial when current is non-positive`() {
        val next = nextExponentialBackoffMs(
            currentBackoffMs = 0L,
            initialBackoffMs = 1_500L,
            maxBackoffMs = 6_000L,
        )

        assertEquals(1_500L, next)
    }

    @Test
    fun `nextExponentialBackoffMs doubles current until cap`() {
        val first = nextExponentialBackoffMs(
            currentBackoffMs = 1_500L,
            initialBackoffMs = 1_500L,
            maxBackoffMs = 6_000L,
        )
        val second = nextExponentialBackoffMs(
            currentBackoffMs = first,
            initialBackoffMs = 1_500L,
            maxBackoffMs = 6_000L,
        )
        val third = nextExponentialBackoffMs(
            currentBackoffMs = second,
            initialBackoffMs = 1_500L,
            maxBackoffMs = 6_000L,
        )

        assertEquals(3_000L, first)
        assertEquals(6_000L, second)
        assertEquals(6_000L, third)
    }

    @Test
    fun `nextExponentialBackoffMs respects maximum upper bound`() {
        val next = nextExponentialBackoffMs(
            currentBackoffMs = 10_000L,
            initialBackoffMs = 1_500L,
            maxBackoffMs = 6_000L,
        )

        assertEquals(6_000L, next)
    }
}
