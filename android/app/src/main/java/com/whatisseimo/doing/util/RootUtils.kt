package com.whatisseimo.doing.util

import android.os.Build
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"

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
}
