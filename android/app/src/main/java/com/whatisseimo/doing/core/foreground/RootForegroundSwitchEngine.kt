package com.whatisseimo.doing.core.foreground

class RootForegroundSwitchEngine(
    private val stableConfirmMs: Long = DEFAULT_STABLE_CONFIRM_MS,
) {
    data class ConfirmedSwitch(
        val packageName: String,
        val firstSeenTs: Long,
    )

    private var candidatePackageName: String? = null
    private var candidateFirstSeenTs: Long = 0L
    private var lastReportedPackageName: String? = null

    fun onSample(packageName: String, sampleTs: Long): ConfirmedSwitch? {
        val currentCandidate = candidatePackageName
        if (currentCandidate != packageName) {
            candidatePackageName = packageName
            candidateFirstSeenTs = sampleTs
            return null
        }

        if (sampleTs - candidateFirstSeenTs < stableConfirmMs) {
            return null
        }

        if (lastReportedPackageName == packageName) {
            return null
        }

        lastReportedPackageName = packageName
        return ConfirmedSwitch(
            packageName = packageName,
            firstSeenTs = candidateFirstSeenTs,
        )
    }

    fun clearCandidate() {
        candidatePackageName = null
        candidateFirstSeenTs = 0L
    }

    fun reset() {
        clearCandidate()
        lastReportedPackageName = null
    }

    fun lastReportedPackageName(): String? = lastReportedPackageName

    companion object {
        const val DEFAULT_STABLE_CONFIRM_MS = 1_500L
    }
}
