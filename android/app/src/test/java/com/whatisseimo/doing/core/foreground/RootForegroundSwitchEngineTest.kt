package com.whatisseimo.doing.core.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootForegroundSwitchEngineTest {
    @Test
    fun `transient switch shorter than stable window is ignored`() {
        val engine = RootForegroundSwitchEngine(stableConfirmMs = 1_500)

        assertNull(engine.onSample(packageName = "app.a", sampleTs = 1_000L))
        assertNull(engine.onSample(packageName = "app.b", sampleTs = 1_300L))
        assertNull(engine.onSample(packageName = "app.a", sampleTs = 1_700L))
    }

    @Test
    fun `stable switch reports first seen timestamp`() {
        val engine = RootForegroundSwitchEngine(stableConfirmMs = 1_500)

        assertNull(engine.onSample(packageName = "app.b", sampleTs = 10_000L))
        val confirmed = engine.onSample(packageName = "app.b", sampleTs = 11_600L)

        requireNotNull(confirmed)
        assertEquals("app.b", confirmed.packageName)
        assertEquals(10_000L, confirmed.firstSeenTs)
    }

    @Test
    fun `same package is not emitted twice`() {
        val engine = RootForegroundSwitchEngine(stableConfirmMs = 1_500)

        assertNull(engine.onSample(packageName = "app.b", sampleTs = 20_000L))
        requireNotNull(engine.onSample(packageName = "app.b", sampleTs = 21_600L))

        assertNull(engine.onSample(packageName = "app.b", sampleTs = 23_500L))
    }
}
