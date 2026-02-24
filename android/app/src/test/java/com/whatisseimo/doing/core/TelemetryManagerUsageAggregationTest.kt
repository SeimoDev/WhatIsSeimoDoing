package com.whatisseimo.doing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryManagerUsageAggregationTest {
    @Test
    fun `aggregateUsageByPackageFromEvents handles package switches without explicit background`() {
        val usage = aggregateUsageByPackageFromEvents(
            events = listOf(
                UsageEventSample(packageName = "com.example.a", timestampMs = 1_000L, isForeground = true),
                UsageEventSample(packageName = "com.example.b", timestampMs = 4_000L, isForeground = true),
                UsageEventSample(packageName = "com.example.b", timestampMs = 7_000L, isForeground = false),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )

        assertEquals(3_000L, usage["com.example.a"])
        assertEquals(3_000L, usage["com.example.b"])
    }

    @Test
    fun `aggregateUsageByPackageFromEvents clips sessions that start before window`() {
        val usage = aggregateUsageByPackageFromEvents(
            events = listOf(
                UsageEventSample(packageName = "com.example.a", timestampMs = 100L, isForeground = true),
                UsageEventSample(packageName = "com.example.a", timestampMs = 2_500L, isForeground = false),
            ),
            windowStartMs = 1_000L,
            windowEndMs = 5_000L,
        )

        assertEquals(1_500L, usage["com.example.a"])
    }

    @Test
    fun `trimUsageByElapsedBudget keeps top usage within elapsed budget`() {
        val trimmed = trimUsageByElapsedBudget(
            usageByPackage = mapOf(
                "com.example.a" to 5_000L,
                "com.example.b" to 3_000L,
                "com.example.c" to 1_000L,
            ),
            elapsedMs = 6_000L,
        )

        assertEquals(2, trimmed.size)
        assertEquals(5_000L, trimmed["com.example.a"])
        assertEquals(1_000L, trimmed["com.example.b"])
    }
}
