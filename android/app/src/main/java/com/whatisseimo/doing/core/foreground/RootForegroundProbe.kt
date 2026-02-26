package com.whatisseimo.doing.core.foreground

import com.whatisseimo.doing.util.SuCommandResult

class RootForegroundProbe(
    private val commandRunner: (String, Long) -> SuCommandResult,
    private val packageFilter: (String) -> Boolean = { true },
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val reselectFailureThreshold: Int = DEFAULT_RESELECT_FAILURE_THRESHOLD,
) {
    enum class ProbeType(
        val command: String,
        val keywords: List<String>,
    ) {
        ACTIVITY_ACTIVITIES(
            command = "/system/bin/dumpsys activity activities",
            keywords = listOf("topResumedActivity", "mResumedActivity"),
        ),
        WINDOW_WINDOWS(
            command = "/system/bin/dumpsys window windows",
            keywords = listOf("mCurrentFocus", "mFocusedApp"),
        ),
        ACTIVITY_TOP(
            command = "/system/bin/dumpsys activity top",
            keywords = listOf("ACTIVITY "),
        ),
    }

    data class ProbeSample(
        val packageName: String?,
        val probeType: ProbeType,
        val parseMatched: Boolean,
        val exitCode: Int,
        val timedOut: Boolean,
        val commandOutput: String,
    )

    private var selectedProbe: ProbeType? = null
    private var consecutiveParseFailures = 0

    fun poll(): ProbeSample {
        val current = selectedProbe
        if (current == null) {
            return calibrateProbe()
        }

        val sample = runProbe(current)
        if (sample.parseMatched) {
            consecutiveParseFailures = 0
            return sample
        }

        consecutiveParseFailures += 1
        if (consecutiveParseFailures < reselectFailureThreshold) {
            return sample
        }

        selectedProbe = null
        consecutiveParseFailures = 0
        return calibrateProbe()
    }

    fun selectedProbeType(): ProbeType? = selectedProbe

    private fun calibrateProbe(): ProbeSample {
        var lastSample: ProbeSample? = null
        for (probeType in PROBE_ORDER) {
            val sample = runProbe(probeType)
            lastSample = sample
            if (sample.parseMatched) {
                selectedProbe = probeType
                consecutiveParseFailures = 0
                return sample
            }
        }

        return lastSample
            ?: ProbeSample(
                packageName = null,
                probeType = ProbeType.ACTIVITY_ACTIVITIES,
                parseMatched = false,
                exitCode = -1,
                timedOut = false,
                commandOutput = "",
            )
    }

    private fun runProbe(probeType: ProbeType): ProbeSample {
        val result = commandRunner(probeType.command, commandTimeoutMs)
        val output = buildString {
            if (result.stdout.isNotBlank()) {
                append(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) {
                    append('\n')
                }
                append(result.stderr)
            }
        }

        val rawPackageName = if (!result.timedOut && result.exitCode == 0) {
            extractPackageName(output, probeType.keywords)
        } else {
            null
        }
        val packageName = rawPackageName?.takeIf(packageFilter)
        val parseMatched = !rawPackageName.isNullOrBlank()

        return ProbeSample(
            packageName = packageName,
            probeType = probeType,
            parseMatched = parseMatched,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            commandOutput = output,
        )
    }

    private fun extractPackageName(
        output: String,
        keywords: List<String>,
    ): String? {
        if (output.isBlank()) {
            return null
        }

        if (keywords.isEmpty()) {
            return PACKAGE_COMPONENT_REGEX.find(output)?.groupValues?.get(1)
        }

        var fallback: String? = null
        val keywordMatchedPackage = arrayOfNulls<String>(keywords.size)

        for (line in output.lineSequence()) {
            val packageName = PACKAGE_COMPONENT_REGEX.find(line)?.groupValues?.get(1) ?: continue
            fallback = packageName

            keywords.forEachIndexed { index, keyword ->
                if (line.contains(keyword)) {
                    keywordMatchedPackage[index] = packageName
                }
            }
        }

        for (index in keywords.indices) {
            val byKeyword = keywordMatchedPackage[index]
            if (!byKeyword.isNullOrBlank()) {
                return byKeyword
            }
        }

        return fallback
    }

    companion object {
        private val PROBE_ORDER = listOf(
            ProbeType.ACTIVITY_ACTIVITIES,
            ProbeType.WINDOW_WINDOWS,
            ProbeType.ACTIVITY_TOP,
        )

        private val PACKAGE_COMPONENT_REGEX =
            Regex("([a-zA-Z0-9_\\.]+)/[a-zA-Z0-9_\\.$]+")

        private const val DEFAULT_COMMAND_TIMEOUT_MS = 1_200L
        private const val DEFAULT_RESELECT_FAILURE_THRESHOLD = 3
    }
}
