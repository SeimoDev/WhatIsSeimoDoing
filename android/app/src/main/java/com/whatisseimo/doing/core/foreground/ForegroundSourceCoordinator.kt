package com.whatisseimo.doing.core.foreground

enum class ForegroundSource {
    ROOT,
    ACCESSIBILITY,
}

class ForegroundSourceCoordinator(
    private val rootAvailable: Boolean,
    private val rootHealthGraceMs: Long = DEFAULT_ROOT_HEALTH_GRACE_MS,
    private val rootFailThreshold: Int = DEFAULT_ROOT_FAIL_THRESHOLD,
    private val rootRecoverySuccessThreshold: Int = DEFAULT_ROOT_RECOVERY_SUCCESS_THRESHOLD,
) {
    private var lastReportedPackageName: String? = null
    private var lastReportedTs: Long = 0L

    private var rootLastSuccessTs: Long = 0L
    private var rootFailureCount: Int = 0
    private var rootRecoverySuccessCount: Int = 0
    private var rootDegraded: Boolean = rootAvailable

    @Synchronized
    fun isRootAvailable(): Boolean = rootAvailable

    @Synchronized
    fun markRootSampleSuccess(now: Long = System.currentTimeMillis()) {
        if (!rootAvailable) {
            return
        }

        rootLastSuccessTs = now
        if (rootDegraded) {
            rootRecoverySuccessCount += 1
            if (rootRecoverySuccessCount >= rootRecoverySuccessThreshold) {
                rootDegraded = false
                rootFailureCount = 0
                rootRecoverySuccessCount = 0
            }
            return
        }

        rootFailureCount = 0
        rootRecoverySuccessCount = 0
    }

    @Synchronized
    fun markRootSampleFailure(now: Long = System.currentTimeMillis()) {
        if (!rootAvailable) {
            return
        }

        if (rootLastSuccessTs > 0L && now - rootLastSuccessTs > rootHealthGraceMs) {
            rootDegraded = true
            rootRecoverySuccessCount = 0
        }

        rootFailureCount += 1
        if (rootFailureCount >= rootFailThreshold) {
            rootDegraded = true
            rootRecoverySuccessCount = 0
        }
    }

    @Synchronized
    fun isRootHealthy(now: Long = System.currentTimeMillis()): Boolean {
        if (!rootAvailable) {
            return false
        }
        if (rootDegraded) {
            return false
        }
        if (rootLastSuccessTs <= 0L) {
            return false
        }

        return now - rootLastSuccessTs <= rootHealthGraceMs
    }

    @Synchronized
    fun allowAccessibilityFallback(now: Long = System.currentTimeMillis()): Boolean {
        if (!rootAvailable) {
            return true
        }

        return !isRootHealthy(now)
    }

    @Synchronized
    fun tryAccept(
        source: ForegroundSource,
        packageName: String,
        ts: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (packageName.isBlank() || ts <= 0L) {
            return false
        }

        if (source == ForegroundSource.ACCESSIBILITY && !allowAccessibilityFallback(now)) {
            return false
        }

        if (lastReportedPackageName == packageName) {
            return false
        }

        lastReportedPackageName = packageName
        lastReportedTs = ts
        return true
    }

    @Synchronized
    fun lastReportedPackageName(): String? = lastReportedPackageName

    @Synchronized
    fun lastReportedTimestamp(): Long = lastReportedTs

    companion object {
        const val DEFAULT_ROOT_HEALTH_GRACE_MS = 4_000L
        const val DEFAULT_ROOT_FAIL_THRESHOLD = 6
        const val DEFAULT_ROOT_RECOVERY_SUCCESS_THRESHOLD = 3
    }
}
