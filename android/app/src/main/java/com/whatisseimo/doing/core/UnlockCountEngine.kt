package com.whatisseimo.doing.core

class UnlockCountEngine(
    private val minEventGapMs: Long = DEFAULT_MIN_EVENT_GAP_MS,
) {
    private var initialized = false
    private var previousLockedOrNonInteractive = true
    private var lastCountTs: Long? = null

    fun onSample(interactive: Boolean, keyguardLocked: Boolean, sampleTs: Long): Boolean {
        val currentLockedOrNonInteractive = !interactive || keyguardLocked

        if (!initialized) {
            initialized = true
            previousLockedOrNonInteractive = currentLockedOrNonInteractive
            return false
        }

        val transitionedToUnlocked =
            previousLockedOrNonInteractive && !currentLockedOrNonInteractive
        previousLockedOrNonInteractive = currentLockedOrNonInteractive

        if (!transitionedToUnlocked) {
            return false
        }

        val previousCountTs = lastCountTs
        if (previousCountTs != null && sampleTs - previousCountTs < minEventGapMs) {
            return false
        }

        lastCountTs = sampleTs
        return true
    }

    fun reset() {
        initialized = false
        previousLockedOrNonInteractive = true
        lastCountTs = null
    }

    companion object {
        const val DEFAULT_MIN_EVENT_GAP_MS = 2_000L
    }
}
