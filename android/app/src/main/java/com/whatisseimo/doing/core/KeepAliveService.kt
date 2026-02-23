package com.whatisseimo.doing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whatisseimo.doing.R
import com.whatisseimo.doing.WhatIsSeimoDoingApp
import com.whatisseimo.doing.util.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class KeepAliveService : Service() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketClient = DeviceSocketClient()

    private lateinit var telemetryManager: TelemetryManager
    private var workersStarted = false

    override fun onCreate() {
        super.onCreate()
        telemetryManager = (application as WhatIsSeimoDoingApp).graph.telemetryManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!workersStarted) {
            workersStarted = true
            startWorkers()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        socketClient.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorkers() {
        serviceScope.launch {
            runCatching { telemetryManager.ensureRegistered() }
            runCatching { telemetryManager.flushQueue() }
            runCatching { connectSocket() }
                .onFailure { error ->
                    Log.e(TAG, "Initial socket connect failed", error)
                }

            if (RootUtils.hasRoot()) {
                RootUtils.startKeepAliveGuard(packageName)
            }
        }

        serviceScope.launch {
            while (isActive) {
                runCatching { telemetryManager.sendHeartbeat() }
                delay(30_000)
            }
        }

        serviceScope.launch {
            while (isActive) {
                runCatching { telemetryManager.sendDailySnapshot() }
                delay(5 * 60_000L)
            }
        }

        serviceScope.launch {
            while (isActive) {
                runCatching { telemetryManager.flushQueue() }
                delay(20_000)
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(30_000)
                runCatching { connectSocket() }
                    .onFailure { error ->
                        Log.e(TAG, "Periodic socket connect failed", error)
                    }
            }
        }
    }

    private suspend fun connectSocket() {
        telemetryManager.ensureRegistered()

        val wsUrl = telemetryManager.currentWsUrl() ?: return
        val accessToken = telemetryManager.currentAccessToken() ?: return

        socketClient.connect(wsUrl = wsUrl, accessToken = accessToken) { requestId ->
            serviceScope.launch {
                handleScreenshotRequest(requestId)
            }
        }
    }

    private suspend fun handleScreenshotRequest(requestId: String) {
        val tempFile = RootUtils.captureScreenshot(
            tempDir = cacheDir,
            fileName = "wisd_${requestId}.png",
        )

        if (tempFile != null && tempFile.exists()) {
            runCatching {
                telemetryManager.uploadScreenshot(requestId, tempFile)
            }
            tempFile.delete()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.foreground_service_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(getString(R.string.foreground_service_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "KeepAliveService"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
