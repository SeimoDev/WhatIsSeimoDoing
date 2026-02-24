package com.whatisseimo.doing.core.foreground

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSourceCoordinatorTest {
    @Test
    fun `root healthy state blocks accessibility fallback`() {
        val coordinator = ForegroundSourceCoordinator(rootAvailable = true)

        coordinator.markRootSampleSuccess(now = 1_000L)
        coordinator.markRootSampleSuccess(now = 1_200L)
        coordinator.markRootSampleSuccess(now = 1_400L)

        assertTrue(coordinator.isRootHealthy(now = 1_500L))
        assertFalse(coordinator.allowAccessibilityFallback(now = 1_500L))
        assertFalse(
            coordinator.tryAccept(
                source = ForegroundSource.ACCESSIBILITY,
                packageName = "com.example.app",
                ts = 1_500L,
                now = 1_500L,
            ),
        )
    }

    @Test
    fun `accessibility can take over after root failures`() {
        val coordinator = ForegroundSourceCoordinator(rootAvailable = true)

        coordinator.markRootSampleSuccess(now = 1_000L)
        coordinator.markRootSampleSuccess(now = 1_100L)
        coordinator.markRootSampleSuccess(now = 1_200L)

        repeat(6) { index ->
            coordinator.markRootSampleFailure(now = 2_000L + index)
        }

        assertTrue(coordinator.allowAccessibilityFallback(now = 2_100L))
        assertTrue(
            coordinator.tryAccept(
                source = ForegroundSource.ACCESSIBILITY,
                packageName = "com.example.fallback",
                ts = 2_100L,
                now = 2_100L,
            ),
        )
    }

    @Test
    fun `dedupe works across different sources`() {
        val coordinator = ForegroundSourceCoordinator(rootAvailable = false)

        assertTrue(
            coordinator.tryAccept(
                source = ForegroundSource.ACCESSIBILITY,
                packageName = "com.example.same",
                ts = 1_000L,
                now = 1_000L,
            ),
        )
        assertFalse(
            coordinator.tryAccept(
                source = ForegroundSource.ROOT,
                packageName = "com.example.same",
                ts = 1_500L,
                now = 1_500L,
            ),
        )
        assertTrue(
            coordinator.tryAccept(
                source = ForegroundSource.ROOT,
                packageName = "com.example.next",
                ts = 2_000L,
                now = 2_000L,
            ),
        )
    }
}
