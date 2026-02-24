package com.whatisseimo.doing.core.foreground

import com.whatisseimo.doing.util.SuCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootForegroundProbeParserTest {
    @Test
    fun `extracts package from AOSP mResumedActivity`() {
        val probe = RootForegroundProbe(commandRunner = { command, _ ->
            when (command) {
                "dumpsys activity activities" -> SuCommandResult(
                    exitCode = 0,
                    stdout = "mResumedActivity: ActivityRecord{abc u0 com.example.app/.MainActivity t42}",
                    stderr = "",
                    timedOut = false,
                )

                else -> SuCommandResult(0, "", "", false)
            }
        })

        val sample = probe.poll()
        assertEquals(RootForegroundProbe.ProbeType.ACTIVITY_ACTIVITIES, sample.probeType)
        assertEquals("com.example.app", sample.packageName)
        assertTrue(sample.parseMatched)
    }

    @Test
    fun `falls back to window probe and parses MIUI current focus`() {
        val probe = RootForegroundProbe(commandRunner = { command, _ ->
            when (command) {
                "dumpsys activity activities" -> SuCommandResult(
                    exitCode = 0,
                    stdout = "no resumed line",
                    stderr = "",
                    timedOut = false,
                )

                "dumpsys window windows" -> SuCommandResult(
                    exitCode = 0,
                    stdout = "mCurrentFocus=Window{123 u0 com.miui.home/com.miui.home.launcher.Launcher}",
                    stderr = "",
                    timedOut = false,
                )

                else -> SuCommandResult(0, "", "", false)
            }
        })

        val sample = probe.poll()
        assertEquals(RootForegroundProbe.ProbeType.WINDOW_WINDOWS, sample.probeType)
        assertEquals("com.miui.home", sample.packageName)
    }

    @Test
    fun `systemui sample can be filtered while parse still matches`() {
        val probe = RootForegroundProbe(
            commandRunner = { command, _ ->
                when (command) {
                    "dumpsys activity activities" -> SuCommandResult(
                        exitCode = 0,
                        stdout = "topResumedActivity: ActivityRecord{abc u0 com.android.systemui/.recents.RecentsActivity t88}",
                        stderr = "",
                        timedOut = false,
                    )

                    else -> SuCommandResult(0, "", "", false)
                }
            },
            packageFilter = { packageName ->
                packageName != "com.android.systemui"
            },
        )

        val sample = probe.poll()
        assertTrue(sample.parseMatched)
        assertNull(sample.packageName)
    }

    @Test
    fun `re-calibrates probe after three consecutive parse failures`() {
        var activityCalls = 0
        val probe = RootForegroundProbe(commandRunner = { command, _ ->
            when (command) {
                "dumpsys activity activities" -> {
                    activityCalls += 1
                    if (activityCalls == 1) {
                        SuCommandResult(
                            exitCode = 0,
                            stdout = "mResumedActivity: ActivityRecord{abc u0 com.example.first/.MainActivity t7}",
                            stderr = "",
                            timedOut = false,
                        )
                    } else {
                        SuCommandResult(
                            exitCode = 0,
                            stdout = "no activity component",
                            stderr = "",
                            timedOut = false,
                        )
                    }
                }

                "dumpsys window windows" -> SuCommandResult(
                    exitCode = 0,
                    stdout = "mFocusedApp=AppWindowToken{token u0 com.example.second/.HomeActivity}",
                    stderr = "",
                    timedOut = false,
                )

                else -> SuCommandResult(0, "", "", false)
            }
        })

        val first = probe.poll()
        assertEquals("com.example.first", first.packageName)

        probe.poll()
        probe.poll()
        val reselected = probe.poll()

        assertEquals(RootForegroundProbe.ProbeType.WINDOW_WINDOWS, reselected.probeType)
        assertEquals("com.example.second", reselected.packageName)
    }
}
