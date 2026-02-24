package com.whatisseimo.doing.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockCountEngineTest {
    @Test
    fun `first sample never counts`() {
        val engine = UnlockCountEngine(minEventGapMs = 2_000L)
        assertFalse(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 1_000L))
    }

    @Test
    fun `locked to unlocked transition increments once`() {
        val engine = UnlockCountEngine(minEventGapMs = 2_000L)

        assertFalse(engine.onSample(interactive = false, keyguardLocked = true, sampleTs = 1_000L))
        assertTrue(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 2_000L))
    }

    @Test
    fun `continuous unlocked state does not double count`() {
        val engine = UnlockCountEngine(minEventGapMs = 2_000L)

        assertFalse(engine.onSample(interactive = false, keyguardLocked = true, sampleTs = 1_000L))
        assertTrue(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 3_500L))
        assertFalse(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 4_500L))
    }

    @Test
    fun `jitter shorter than min gap is ignored`() {
        val engine = UnlockCountEngine(minEventGapMs = 2_000L)

        assertFalse(engine.onSample(interactive = false, keyguardLocked = true, sampleTs = 1_000L))
        assertTrue(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 2_000L))

        assertFalse(engine.onSample(interactive = false, keyguardLocked = true, sampleTs = 2_500L))
        assertFalse(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 3_200L))
    }

    @Test
    fun `interactive but keyguard locked only counts after keyguard unlock`() {
        val engine = UnlockCountEngine(minEventGapMs = 2_000L)

        assertFalse(engine.onSample(interactive = true, keyguardLocked = true, sampleTs = 1_000L))
        assertFalse(engine.onSample(interactive = true, keyguardLocked = true, sampleTs = 2_000L))
        assertTrue(engine.onSample(interactive = true, keyguardLocked = false, sampleTs = 3_500L))
    }
}
