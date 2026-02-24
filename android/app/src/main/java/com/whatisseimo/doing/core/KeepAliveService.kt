package com.whatisseimo.doing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whatisseimo.doing.R
import com.whatisseimo.doing.WhatIsSeimoDoingApp
import com.whatisseimo.doing.core.foreground.ForegroundSource
import com.whatisseimo.doing.core.foreground.ForegroundSourceCoordinator
import com.whatisseimo.doing.core.foreground.RootForegroundProbe
import com.whatisseimo.doing.core.foreground.RootForegroundSwitchEngine
import com.whatisseimo.doing.util.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class KeepAliveService : Service() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketClient = DeviceSocketClient()

    private lateinit var telemetryManager: TelemetryManager
    private lateinit var foregroundCoordinator: ForegroundSourceCoordinator
    private lateinit var rootForegroundProbe: RootForegroundProbe
    private val rootForegroundSwitchEngine = RootForegroundSwitchEngine(stableConfirmMs = ROOT_STABLE_CONFIRM_MS)
    private val unlockCountEngine = UnlockCountEngine(minEventGapMs = UNLOCK_MIN_EVENT_GAP_MS)
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager

    private var workersStarted = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val imePackagesCache = mutableSetOf<String>()
    private var imeCacheUpdatedAt = 0L
    private var lastUnlockImmediateSnapshotAt = 0L
    private var lastReportedScreenLocked: Boolean? = null
    private val appCatalogSyncMutex = Mutex()
    @Volatile
    private var activeReconnectSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as WhatIsSeimoDoingApp).graph
        telemetryManager = graph.telemetryManager
        foregroundCoordinator = graph.foregroundSourceCoordinator
        rootForegroundProbe = RootForegroundProbe(
            commandRunner = RootUtils::runSuCommandForOutput,
            packageFilter = this::isRootForegroundPackageAllowed,
        )
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)

        createNotificationChannel()
        registerNetworkCallback()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!workersStarted) {
            workersStarted = true
            startWorkers()
        }

        when (intent?.action) {
            ACTION_RECONNECT_SOCKET -> {
                val reconnectSessionId = intent.getStringExtra(EXTRA_RECONNECT_SESSION_ID)
                activeReconnectSessionId = reconnectSessionId
                reconnectSessionId?.let { sessionId ->
                    emitReconnectEvent(
                        sessionId = sessionId,
                        message = "Reconnect requested",
                    )
                }
                serviceScope.launch {
                    runCatching { forceReconnectSocket() }
                        .onFailure { error ->
                            Log.e(TAG, "Manual reconnect failed", error)
                            reconnectSessionId?.let { sessionId ->
                                emitReconnectEvent(
                                    sessionId = sessionId,
                                    message = "Manual reconnect failed: ${error.message ?: "unknown"}",
                                    isTerminal = true,
                                    isSuccess = false,
                                )
                                activeReconnectSessionId = null
                            }
                        }
                }
            }

            ACTION_SYNC_APP_CATALOG -> {
                serviceScope.launch {
                    runCatching { runManualAppCatalogSync() }
                        .onFailure { Log.e(TAG, "Manual app catalog sync failed", it) }
                }
            }

            ACTION_START, null -> Unit
            else -> Unit
        }

        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        socketClient.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorkers() {
        serviceScope.launch {
            runCatching { telemetryManager.ensureRegistered() }
            runCatching { telemetryManager.flushQueue() }
            runCatching { ensureSocketConnected() }
                .onFailure { error ->
                    Log.e(TAG, "Initial socket connect failed", error)
                }

            if (foregroundCoordinator.isRootAvailable()) {
                RootUtils.startKeepAliveGuard(packageName)
            }
        }

        serviceScope.launch {
            runRootForegroundWorker()
        }

        serviceScope.launch {
            runUnlockMonitorWorker()
        }

        serviceScope.launch {
            runScreenStateWorker()
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
                delay(SOCKET_WATCHDOG_INTERVAL_MS)
                runCatching {
                    if (!socketClient.isConnected() && !socketClient.isConnecting()) {
                        ensureSocketConnected()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Periodic socket connect failed", error)
                }
            }
        }
    }

    private suspend fun runRootForegroundWorker() {
        if (!foregroundCoordinator.isRootAvailable()) {
            Log.i(TAG, "FG_ROOT root unavailable, accessibility fallback only")
            return
        }

        Log.i(TAG, "FG_ROOT root probe worker started")
        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            if (!powerManager.isInteractive) {
                rootForegroundSwitchEngine.clearCandidate()
                delay(ROOT_SCREEN_OFF_INTERVAL_MS)
                continue
            }

            val sample = rootForegroundProbe.poll()
            if (sample.parseMatched) {
                foregroundCoordinator.markRootSampleSuccess(now)
                val packageName = sample.packageName
                if (packageName != null) {
                    val confirmed = rootForegroundSwitchEngine.onSample(
                        packageName = packageName,
                        sampleTs = now,
                    )

                    if (
                        confirmed != null &&
                        foregroundCoordinator.tryAccept(
                            source = ForegroundSource.ROOT,
                            packageName = confirmed.packageName,
                            ts = confirmed.firstSeenTs,
                            now = now,
                        )
                    ) {
                        runCatching {
                            telemetryManager.reportForegroundSwitch(
                                packageName = confirmed.packageName,
                                ts = confirmed.firstSeenTs,
                            )
                        }.onFailure {
                            Log.e(TAG, "FG_ROOT failed to report package=${confirmed.packageName}", it)
                        }
                    }
                }
            } else {
                foregroundCoordinator.markRootSampleFailure(now)
                val fallbackEnabled = foregroundCoordinator.allowAccessibilityFallback(now)
                Log.d(
                    TAG,
                    "FG_ROOT probe miss source=${sample.probeType} " +
                        "exit=${sample.exitCode} timeout=${sample.timedOut} fallback=$fallbackEnabled",
                )
            }

            delay(ROOT_POLL_INTERVAL_MS)
        }
    }

    private suspend fun runUnlockMonitorWorker() {
        Log.i(TAG, "UNLOCK_TRACK worker started")

        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            val interactive = powerManager.isInteractive
            val keyguardLocked = keyguardManager.isKeyguardLocked
            val shouldCount = unlockCountEngine.onSample(
                interactive = interactive,
                keyguardLocked = keyguardLocked,
                sampleTs = now,
            )

            Log.d(
                TAG,
                "UNLOCK_TRACK sample interactive=$interactive keyguardLocked=$keyguardLocked shouldCount=$shouldCount",
            )

            if (shouldCount) {
                telemetryManager.incrementUnlockCount()
                Log.i(TAG, "UNLOCK_TRACK counted unlock at=$now")

                if (now - lastUnlockImmediateSnapshotAt >= UNLOCK_IMMEDIATE_SNAPSHOT_MIN_INTERVAL_MS) {
                    lastUnlockImmediateSnapshotAt = now
                    runCatching {
                        telemetryManager.sendDailySnapshot()
                    }.onFailure {
                        Log.e(TAG, "UNLOCK_TRACK immediate snapshot failed", it)
                    }
                } else {
                    Log.d(
                        TAG,
                        "UNLOCK_TRACK immediate snapshot throttled intervalMs=${now - lastUnlockImmediateSnapshotAt}",
                    )
                }
            }

            delay(
                if (interactive) {
                    UNLOCK_POLL_INTERVAL_MS
                } else {
                    UNLOCK_POLL_SCREEN_OFF_INTERVAL_MS
                },
            )
        }
    }

    private suspend fun runScreenStateWorker() {
        Log.i(TAG, "SCREEN_STATE worker started")

        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            val isScreenLocked = !powerManager.isInteractive || keyguardManager.isKeyguardLocked
            val shouldReport =
                lastReportedScreenLocked == null || lastReportedScreenLocked != isScreenLocked

            if (shouldReport) {
                runCatching {
                    telemetryManager.reportScreenState(
                        isScreenLocked = isScreenLocked,
                        ts = now,
                    )
                    lastReportedScreenLocked = isScreenLocked
                    Log.i(TAG, "SCREEN_STATE reported isLocked=$isScreenLocked ts=$now")
                }.onFailure {
                    Log.e(TAG, "SCREEN_STATE report failed isLocked=$isScreenLocked", it)
                }
            }

            delay(SCREEN_STATE_POLL_INTERVAL_MS)
        }
    }

    private suspend fun runManualAppCatalogSync() {
        appCatalogSyncMutex.withLock {
            Log.i(TAG, "APP_SYNC manual trigger start")
            telemetryManager.syncInstalledAppCatalog(forceUploadIcons = true)
            Log.i(TAG, "APP_SYNC manual trigger completed")
        }
    }

    private fun isRootForegroundPackageAllowed(packageName: String): Boolean {
        if (packageName == this.packageName) {
            return false
        }
        if (packageName in ROOT_TRANSIENT_SYSTEM_UI_PACKAGES) {
            return false
        }
        if (isInputMethodPackage(packageName)) {
            return false
        }
        return true
    }

    private fun isInputMethodPackage(packageName: String): Boolean {
        refreshImePackagesCacheIfNeeded()
        return packageName in imePackagesCache
    }

    private fun refreshImePackagesCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (imePackagesCache.isNotEmpty() && now - imeCacheUpdatedAt < ROOT_IME_CACHE_TTL_MS) {
            return
        }

        val next = mutableSetOf<String>()

        val enabledRaw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ).orEmpty()
        enabledRaw.split(':')
            .mapNotNull { flattenComponentToPackage(it) }
            .forEach { next.add(it) }

        val defaultIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        flattenComponentToPackage(defaultIme)?.let { next.add(it) }

        imePackagesCache.clear()
        imePackagesCache.addAll(next)
        imeCacheUpdatedAt = now
    }

    private fun flattenComponentToPackage(raw: String): String? {
        if (raw.isBlank()) {
            return null
        }

        return raw.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private suspend fun ensureSocketConnected() {
        telemetryManager.ensureRegistered()

        val wsUrl = telemetryManager.currentWsUrl() ?: return
        val accessToken = telemetryManager.currentAccessToken() ?: return

        socketClient.connectIfNeeded(
            wsUrl = wsUrl,
            accessToken = accessToken,
            onScreenshotRequest = { requestId ->
                serviceScope.launch {
                    handleScreenshotRequest(requestId)
                }
            },
            onConnectionEvent = this::onSocketConnectionEvent,
        )
    }

    private suspend fun forceReconnectSocket() {
        telemetryManager.ensureRegistered()

        val reconnectSessionId = activeReconnectSessionId

        val wsUrl = telemetryManager.currentWsUrl()
        if (wsUrl.isNullOrBlank()) {
            reconnectSessionId?.let { sessionId ->
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Reconnect failed: websocket url unavailable",
                    isTerminal = true,
                    isSuccess = false,
                )
                activeReconnectSessionId = null
            }
            return
        }

        val accessToken = telemetryManager.currentAccessToken()
        if (accessToken.isNullOrBlank()) {
            reconnectSessionId?.let { sessionId ->
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Reconnect failed: access token unavailable",
                    isTerminal = true,
                    isSuccess = false,
                )
                activeReconnectSessionId = null
            }
            return
        }

        socketClient.connectIfNeeded(
            wsUrl = wsUrl,
            accessToken = accessToken,
            onScreenshotRequest = { requestId ->
                serviceScope.launch {
                    handleScreenshotRequest(requestId)
                }
            },
            onConnectionEvent = this::onSocketConnectionEvent,
        )
        reconnectSessionId?.let { sessionId ->
            emitReconnectEvent(
                sessionId = sessionId,
                message = "Reconnect command sent",
            )
        }
        socketClient.forceReconnect()
    }

    private fun onSocketConnectionEvent(event: String, detail: String?) {
        val suffix = detail?.let { " detail=$it" }.orEmpty()
        Log.d(TAG, "Socket event=$event$suffix")

        val sessionId = activeReconnectSessionId ?: return
        when (event) {
            DeviceSocketClient.EVENT_RECONNECT_ATTEMPT -> {
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Reconnect attempt started${detailSuffix(detail)}",
                )
            }

            DeviceSocketClient.EVENT_CONNECT -> {
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Reconnect successful",
                    isTerminal = true,
                    isSuccess = true,
                )
                activeReconnectSessionId = null
            }

            DeviceSocketClient.EVENT_CONNECT_ERROR -> {
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Socket connect error${detailSuffix(detail)}",
                )
            }

            DeviceSocketClient.EVENT_RECONNECT_FAILED -> {
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Reconnect failed${detailSuffix(detail)}",
                    isTerminal = true,
                    isSuccess = false,
                )
                activeReconnectSessionId = null
            }

            DeviceSocketClient.EVENT_DISCONNECT -> {
                emitReconnectEvent(
                    sessionId = sessionId,
                    message = "Socket disconnected${detailSuffix(detail)}",
                )
            }
        }
    }

    private fun detailSuffix(detail: String?): String {
        return detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    }

    private fun emitReconnectEvent(
        sessionId: String,
        message: String,
        isTerminal: Boolean = false,
        isSuccess: Boolean? = null,
    ) {
        SocketReconnectEventBus.emit(
            SocketReconnectEvent(
                sessionId = sessionId,
                message = message,
                isTerminal = isTerminal,
                isSuccess = isSuccess,
            ),
        )
    }

    private suspend fun handleScreenshotRequest(requestId: String) {
        val tempFile = RootUtils.captureScreenshot(
            tempDir = cacheDir,
            fileName = "wisd_${requestId}.png",
        )

        if (tempFile != null && tempFile.exists()) {
            val uploadFile = optimizeScreenshotForUpload(requestId, tempFile)
            runCatching {
                telemetryManager.uploadScreenshot(requestId, uploadFile)
            }.onFailure {
                Log.e(TAG, "Failed to upload screenshot requestId=$requestId", it)
            }
            if (uploadFile.absolutePath != tempFile.absolutePath) {
                uploadFile.delete()
            }
            tempFile.delete()
        }
    }

    private fun optimizeScreenshotForUpload(requestId: String, sourceFile: File): File {
        return runCatching {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                return sourceFile
            }

            val sampleSize = calculateSampleSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxLongEdge = SCREENSHOT_UPLOAD_MAX_LONG_EDGE_PX,
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
                ?: return sourceFile

            val compressedFile = File(cacheDir, "wisd_${requestId}.jpg")
            val saved = FileOutputStream(compressedFile).use { output ->
                decoded.compress(
                    Bitmap.CompressFormat.JPEG,
                    SCREENSHOT_UPLOAD_JPEG_QUALITY,
                    output,
                )
            }
            decoded.recycle()

            if (!saved || !compressedFile.exists() || compressedFile.length() <= 0L) {
                compressedFile.delete()
                sourceFile
            } else if (compressedFile.length() >= sourceFile.length()) {
                compressedFile.delete()
                sourceFile
            } else {
                compressedFile
            }
        }.getOrElse {
            Log.w(TAG, "Screenshot optimize failed, fallback to source file", it)
            sourceFile
        }
    }

    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxLongEdge: Int,
    ): Int {
        val longEdge = max(sourceWidth, sourceHeight)
        if (longEdge <= maxLongEdge) {
            return 1
        }

        var sampleSize = 1
        var current = longEdge
        while (current > maxLongEdge && sampleSize < 8) {
            sampleSize *= 2
            current /= 2
        }
        return sampleSize
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    runCatching { ensureSocketConnected() }
                        .onFailure { Log.e(TAG, "Socket reconnect on network available failed", it) }
                }
            }
        }

        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            connectivityManager = manager
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "registerDefaultNetworkCallback failed", it)
        }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivityManager ?: return
        val callback = networkCallback ?: return

        runCatching {
            manager.unregisterNetworkCallback(callback)
        }

        connectivityManager = null
        networkCallback = null
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
            .addAction(
                R.drawable.ic_stat_monitor,
                getString(R.string.foreground_service_action_reconnect),
                buildReconnectPendingIntent(),
            )
            .addAction(
                R.drawable.ic_stat_monitor,
                getString(R.string.foreground_service_action_sync_apps),
                buildSyncAppsPendingIntent(),
            )
            .build()
    }

    private fun buildReconnectPendingIntent(): PendingIntent {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_RECONNECT_SOCKET
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, REQUEST_CODE_RECONNECT, intent, flags)
    }

    private fun buildSyncAppsPendingIntent(): PendingIntent {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_SYNC_APP_CATALOG
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, REQUEST_CODE_SYNC_APPS, intent, flags)
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "KeepAliveService"
        private const val REQUEST_CODE_RECONNECT = 4201
        private const val REQUEST_CODE_SYNC_APPS = 4202

        private const val SOCKET_WATCHDOG_INTERVAL_MS = 10_000L
        private const val SCREENSHOT_UPLOAD_MAX_LONG_EDGE_PX = 1280
        private const val SCREENSHOT_UPLOAD_JPEG_QUALITY = 68

        private const val ROOT_POLL_INTERVAL_MS = 500L
        private const val ROOT_STABLE_CONFIRM_MS = 1_500L
        private const val ROOT_SCREEN_OFF_INTERVAL_MS = 3_000L
        private const val ROOT_IME_CACHE_TTL_MS = 5_000L
        private const val UNLOCK_POLL_INTERVAL_MS = 500L
        private const val UNLOCK_POLL_SCREEN_OFF_INTERVAL_MS = 1_500L
        private const val UNLOCK_MIN_EVENT_GAP_MS = 2_000L
        private const val UNLOCK_IMMEDIATE_SNAPSHOT_MIN_INTERVAL_MS = 10_000L
        private const val SCREEN_STATE_POLL_INTERVAL_MS = 1_000L

        private val ROOT_TRANSIENT_SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.android.permissioncontroller",
        )

        const val ACTION_START = "com.whatisseimo.doing.action.START_KEEPALIVE"
        const val ACTION_RECONNECT_SOCKET = "com.whatisseimo.doing.action.RECONNECT_SOCKET"
        const val ACTION_SYNC_APP_CATALOG = "com.whatisseimo.doing.action.SYNC_APP_CATALOG"
        const val EXTRA_RECONNECT_SESSION_ID = "extra_reconnect_session_id"

        fun start(context: Context) {
            startWithAction(context, ACTION_START)
        }

        fun reconnectSocket(context: Context, sessionId: String? = null) {
            startWithAction(
                context = context,
                action = ACTION_RECONNECT_SOCKET,
                reconnectSessionId = sessionId,
            )
        }

        fun syncAppCatalog(context: Context) {
            startWithAction(context, ACTION_SYNC_APP_CATALOG)
        }

        private fun startWithAction(
            context: Context,
            action: String,
            reconnectSessionId: String? = null,
        ) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                this.action = action
                reconnectSessionId?.let {
                    putExtra(EXTRA_RECONNECT_SESSION_ID, it)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
