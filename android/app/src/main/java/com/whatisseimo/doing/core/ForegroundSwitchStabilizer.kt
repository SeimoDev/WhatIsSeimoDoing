package com.whatisseimo.doing.core

class ForegroundSwitchStabilizer(
    private val stableWindowMs: Long = DEFAULT_STABLE_WINDOW_MS,
) {
    data class Candidate(
        val packageName: String,
        val firstSeenTs: Long,
        val confirmAtTs: Long,
    )

    private var candidate: Candidate? = null
    private var lastReportedPackage: String? = null

    fun stableWindowMs(): Long = stableWindowMs

    fun currentCandidate(): Candidate? = candidate

    fun updateCandidate(packageName: String, firstSeenTs: Long): Candidate? {
        val current = candidate
        if (current?.packageName == packageName) {
            return null
        }

        val next = Candidate(
            packageName = packageName,
            firstSeenTs = firstSeenTs,
            confirmAtTs = firstSeenTs + stableWindowMs,
        )
        candidate = next
        return next
    }

    fun confirm(activePackageName: String?): Candidate? {
        val current = candidate ?: return null
        if (activePackageName != current.packageName) {
            return null
        }

        candidate = null
        if (lastReportedPackage == current.packageName) {
            return null
        }

        lastReportedPackage = current.packageName
        return current
    }

    fun clearCandidate() {
        candidate = null
    }

    fun reset() {
        candidate = null
        lastReportedPackage = null
    }

    companion object {
        const val DEFAULT_STABLE_WINDOW_MS: Long = 5_000L
    }
}
