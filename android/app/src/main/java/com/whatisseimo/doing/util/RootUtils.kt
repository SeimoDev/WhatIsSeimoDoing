package com.whatisseimo.doing.util

import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class SuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

object RootUtils {
    private const val TAG = "RootUtils"
    private const val DEFAULT_SU_OUTPUT_TIMEOUT_MS = 1_200L
    private const val STREAM_JOIN_TIMEOUT_MS = 200L

    fun hasRoot(): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = process.waitFor() == 0
            process.destroy()
            result
        }.getOrElse { false }
    }

    fun startKeepAliveGuard(packageName: String) {
        val cmd = """
            nohup sh -c '
            while true; do
              pidof $packageName >/dev/null 2>&1 || am start-foreground-service -n $packageName/.core.KeepAliveService
              sleep 6
            done
            ' >/data/local/tmp/wisd_guard.log 2>&1 &
        """.trimIndent()

        runSuCommand(cmd)
    }

    fun captureScreenshot(tempDir: File, fileName: String): File? {
        val outFile = File(tempDir, fileName)
        val cmd = "screencap -p ${outFile.absolutePath}"
        val exitCode = runSuCommand(cmd)
        return if (exitCode == 0 && outFile.exists()) outFile else null
    }

    fun runSuCommand(command: String): Int {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val code = process.waitFor()
            process.destroy()
            code
        }.getOrElse {
            Log.e(TAG, "Failed to run su command on ${Build.MODEL}", it)
            -1
        }
    }

    fun runSuCommandForOutput(
        command: String,
        timeoutMs: Long = DEFAULT_SU_OUTPUT_TIMEOUT_MS,
    ): SuCommandResult {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()

            val stdoutThread = thread(start = true, name = "wisd-su-stdout") {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            stdoutBuffer.append(line).append('\n')
                        }
                    }
                }
            }

            val stderrThread = thread(start = true, name = "wisd-su-stderr") {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            stderrBuffer.append(line).append('\n')
                        }
                    }
                }
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }

            stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
            stderrThread.join(STREAM_JOIN_TIMEOUT_MS)

            val exitCode = if (finished) {
                runCatching { process.exitValue() }.getOrDefault(-1)
            } else {
                -1
            }

            process.destroy()
            SuCommandResult(
                exitCode = exitCode,
                stdout = stdoutBuffer.toString().trim(),
                stderr = stderrBuffer.toString().trim(),
                timedOut = !finished,
            )
        }.getOrElse {
            Log.e(TAG, "Failed to run su command with output on ${Build.MODEL}", it)
            SuCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = it.message.orEmpty(),
                timedOut = false,
            )
        }
    }
}
