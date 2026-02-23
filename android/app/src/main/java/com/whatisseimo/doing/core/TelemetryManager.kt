package com.whatisseimo.doing.core

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.whatisseimo.doing.BuildConfig
import com.whatisseimo.doing.data.CounterStore
import com.whatisseimo.doing.data.EventQueueStore
import com.whatisseimo.doing.data.IconCacheStore
import com.whatisseimo.doing.data.SessionStore
import com.whatisseimo.doing.model.DailySnapshotAppItem
import com.whatisseimo.doing.model.DailySnapshotRequest
import com.whatisseimo.doing.model.ForegroundSwitchRequest
import com.whatisseimo.doing.model.HeartbeatRequest
import com.whatisseimo.doing.model.QueuePayload
import com.whatisseimo.doing.model.QueuedEvent
import com.whatisseimo.doing.model.RegisterDeviceRequest
import com.whatisseimo.doing.network.BackendClient
import com.whatisseimo.doing.util.RootUtils
import com.whatisseimo.doing.util.md5Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

class TelemetryManager(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val counterStore: CounterStore,
    private val iconCacheStore: IconCacheStore,
    private val queueStore: EventQueueStore,
    private val backendClient: BackendClient,
) {
    private val packageManager: PackageManager = context.packageManager
    @Volatile
    private var latestUsageByPackage: Map<String, Long> = emptyMap()

    suspend fun ensureRegistered() = withContext(Dispatchers.IO) {
        if (!sessionStore.accessToken.isNullOrBlank() && !sessionStore.deviceId.isNullOrBlank()) {
            return@withContext
        }

        val request = RegisterDeviceRequest(
            deviceCode = resolveDeviceCode(),
            deviceName = Build.MODEL ?: "Android",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            appVersion = BuildConfig.VERSION_NAME,
            rootEnabled = RootUtils.hasRoot(),
        )

        val response = backendClient.registerDevice(request)
        sessionStore.deviceId = response.deviceId
        sessionStore.accessToken = response.accessToken
        sessionStore.refreshToken = response.refreshToken
        sessionStore.wsUrl = response.wsUrl
    }

    suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        ensureRegistered()
        val token = sessionStore.accessToken ?: return@withContext

        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryPct =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100)
                ?: 0
        val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: 0

        val body = HeartbeatRequest(
            ts = System.currentTimeMillis(),
            batteryPct = batteryPct,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            networkType = "unknown",
        )

        backendClient.postHeartbeat(token, body)
    }

    suspend fun reportForegroundSwitch(packageName: String, ts: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            ensureRegistered()

            val token = sessionStore.accessToken ?: return@withContext
            val appLabel = resolveAppLabel(packageName)
            val cachedIconHash = iconCacheStore.getCachedIconHash(packageName)
            val (iconHash, iconBase64) = if (!cachedIconHash.isNullOrBlank()) {
                cachedIconHash to null
            } else {
                val iconBytes = resolveAppIcon(packageName)
                val nextIconHash = iconBytes?.let(::md5Hex)
                val nextIconBase64 = iconBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                if (!nextIconHash.isNullOrBlank()) {
                    iconCacheStore.saveIconHash(packageName, nextIconHash)
                }
                nextIconHash to nextIconBase64
            }

            val usage = latestUsageByPackage[packageName] ?: 0L
            val body = ForegroundSwitchRequest(
                ts = ts,
                packageName = packageName,
                appName = appLabel,
                iconHash = iconHash,
                iconBase64 = iconBase64,
                todayUsageMsAtSwitch = usage,
            )

            runCatching {
                backendClient.postForegroundSwitch(token, body)
            }.onFailure {
                queueStore.enqueue(
                    QueuedEvent(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        payload = QueuePayload.Foreground(body),
                    ),
                )
            }
        }

    suspend fun sendDailySnapshot() = withContext(Dispatchers.IO) {
        ensureRegistered()
        val token = sessionStore.accessToken ?: return@withContext

        val usage = collectUsageByPackage()
        latestUsageByPackage = usage
        val body = DailySnapshotRequest(
            ts = System.currentTimeMillis(),
            totalNotificationCount = counterStore.currentNotificationCount(),
            unlockCount = counterStore.currentUnlockCount(),
            apps = usage.entries
                .sortedByDescending { it.value }
                .map { entry ->
                    DailySnapshotAppItem(
                        packageName = entry.key,
                        usageMsToday = entry.value,
                    )
                },
        )

        runCatching {
            backendClient.postDailySnapshot(token, body)
        }.onFailure {
            queueStore.enqueue(
                QueuedEvent(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    payload = QueuePayload.Snapshot(body),
                ),
            )
        }
    }

    suspend fun flushQueue(maxBatch: Int = 20) = withContext(Dispatchers.IO) {
        ensureRegistered()
        val token = sessionStore.accessToken ?: return@withContext

        val batch = queueStore.popBatch(maxBatch)
        if (batch.isEmpty()) {
            return@withContext
        }

        for ((index, event) in batch.withIndex()) {
            val result = runCatching {
                when (val payload = event.payload) {
                    is QueuePayload.Foreground -> backendClient.postForegroundSwitch(token, payload.body)
                    is QueuePayload.Snapshot -> backendClient.postDailySnapshot(token, payload.body)
                }
            }

            if (result.isFailure) {
                queueStore.prepend(batch.drop(index))
                return@withContext
            }
        }
    }

    suspend fun uploadScreenshot(requestId: String, imageFile: java.io.File) = withContext(Dispatchers.IO) {
        ensureRegistered()
        val token = sessionStore.accessToken ?: return@withContext
        backendClient.uploadScreenshotResult(token, requestId, imageFile)
    }

    fun incrementNotificationCount() {
        counterStore.incrementNotification()
    }

    fun incrementUnlockCount() {
        counterStore.incrementUnlock()
    }

    fun currentWsUrl(): String? = sessionStore.wsUrl

    fun currentAccessToken(): String? = sessionStore.accessToken

    private fun resolveDeviceCode(): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().ifBlank {
            "android-${Build.MODEL}-${Build.SERIAL}".lowercase(Locale.US)
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun resolveAppIcon(packageName: String): ByteArray? {
        val drawable = runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return null

        return drawable.toPngBytes()
    }

    private fun Drawable.toPngBytes(): ByteArray? {
        val bitmap = android.graphics.Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)

        return ByteArrayOutputStream().use { output ->
            val ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            if (ok) output.toByteArray() else null
        }
    }

    private fun collectUsageByPackage(): Map<String, Long> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        val zoneId = java.time.ZoneId.of("Asia/Shanghai")
        val startOfDay = java.time.ZonedDateTime
            .ofInstant(java.time.Instant.ofEpochMilli(now), zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        if (stats.isNullOrEmpty()) {
            return emptyMap()
        }

        return stats
            .filter { usage -> usage.totalTimeInForeground > 0L }
            .associate { usage ->
                usage.packageName to usage.totalTimeInForeground
            }
    }
}
