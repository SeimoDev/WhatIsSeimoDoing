package com.whatisseimo.doing.core

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.Base64
import com.whatisseimo.doing.BuildConfig
import com.whatisseimo.doing.data.CounterStore
import com.whatisseimo.doing.data.EventQueueStore
import com.whatisseimo.doing.data.IconCacheStore
import com.whatisseimo.doing.data.SessionStore
import com.whatisseimo.doing.model.AppCatalogItemRequest
import com.whatisseimo.doing.model.AppCatalogSyncRequest
import com.whatisseimo.doing.model.DailySnapshotAppItem
import com.whatisseimo.doing.model.DailySnapshotRequest
import com.whatisseimo.doing.model.ForegroundSwitchRequest
import com.whatisseimo.doing.model.HeartbeatRequest
import com.whatisseimo.doing.model.QueuePayload
import com.whatisseimo.doing.model.QueuedEvent
import com.whatisseimo.doing.model.RegisterDeviceRequest
import com.whatisseimo.doing.model.ScreenStateRequest
import com.whatisseimo.doing.network.BackendClient
import com.whatisseimo.doing.network.BackendHttpException
import com.whatisseimo.doing.util.RootUtils
import com.whatisseimo.doing.util.md5Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

internal data class RootPackageEntry(
    val packageName: String,
    val apkPath: String?,
)

internal data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val apkPath: String?,
)

internal fun parseRootPackageEntriesFromPmList(stdout: String): List<RootPackageEntry> {
    return stdout
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() && line.startsWith("package:") }
        .mapNotNull { line ->
            val payload = line.removePrefix("package:")
            if (payload.isBlank()) {
                return@mapNotNull null
            }

            val splitIndex = payload.lastIndexOf('=')
            if (splitIndex < 0) {
                val packageName = payload.trim()
                if (packageName.isBlank()) {
                    null
                } else {
                    RootPackageEntry(
                        packageName = packageName,
                        apkPath = null,
                    )
                }
            } else {
                val path = payload.substring(0, splitIndex).ifBlank { null }
                val packageName = payload.substring(splitIndex + 1).trim()
                if (packageName.isBlank()) {
                    null
                } else {
                    RootPackageEntry(
                        packageName = packageName,
                        apkPath = path,
                    )
                }
            }
        }
        .toList()
}

internal fun mergeInstalledAppsForCatalogSync(
    primary: List<InstalledAppInfo>,
    supplement: List<InstalledAppInfo>,
): List<InstalledAppInfo> {
    val merged = linkedMapOf<String, InstalledAppInfo>()

    primary.forEach { app ->
        merged[app.packageName] = app
    }
    supplement.forEach { app ->
        val existing = merged[app.packageName]
        if (existing == null) {
            merged[app.packageName] = app
            return@forEach
        }

        val preferredName = if (
            existing.appName == existing.packageName &&
            app.appName.isNotBlank() &&
            app.appName != app.packageName
        ) {
            app.appName
        } else {
            existing.appName
        }
        merged[app.packageName] = existing.copy(
            appName = preferredName,
            isSystemApp = existing.isSystemApp || app.isSystemApp,
            apkPath = existing.apkPath ?: app.apkPath,
        )
    }

    return merged.values.toList()
}

data class AppCatalogSyncProgress(
    val message: String,
    val totalApps: Int,
    val syncedApps: Int,
    val totalBatches: Int,
    val syncedBatches: Int,
    val appsWithIcons: Int? = null,
)

class TelemetryManager(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val counterStore: CounterStore,
    private val iconCacheStore: IconCacheStore,
    private val queueStore: EventQueueStore,
    private val backendClient: BackendClient,
) {
    private val packageManager: PackageManager = context.packageManager
    private val registrationMutex = Mutex()

    @Volatile
    private var latestUsageByPackage: Map<String, Long> = emptyMap()

    suspend fun ensureRegistered(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        registrationMutex.withLock {
            if (!forceRefresh && hasSession()) {
                return@withLock
            }
            registerDeviceLocked()
        }
    }

    suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
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

        withAuthRetry { token ->
            backendClient.postHeartbeat(token, body)
        }
    }

    suspend fun reportForegroundSwitch(packageName: String, ts: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
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
                withAuthRetry { token ->
                    backendClient.postForegroundSwitch(token, body)
                }
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
            withAuthRetry { token ->
                backendClient.postDailySnapshot(token, body)
            }
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
        val batch = queueStore.popBatch(maxBatch)
        if (batch.isEmpty()) {
            return@withContext
        }

        for ((index, event) in batch.withIndex()) {
            val result = runCatching {
                when (val payload = event.payload) {
                    is QueuePayload.Foreground -> {
                        withAuthRetry { token ->
                            backendClient.postForegroundSwitch(token, payload.body)
                        }
                    }

                    is QueuePayload.Snapshot -> {
                        withAuthRetry { token ->
                            backendClient.postDailySnapshot(token, payload.body)
                        }
                    }
                }
            }

            if (result.isFailure) {
                queueStore.prepend(batch.drop(index))
                return@withContext
            }
        }
    }

    suspend fun uploadScreenshot(requestId: String, imageFile: java.io.File) = withContext(Dispatchers.IO) {
        withAuthRetry { token ->
            backendClient.uploadScreenshotResult(token, requestId, imageFile)
        }
    }

    suspend fun reportScreenState(
        isScreenLocked: Boolean,
        ts: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val body = ScreenStateRequest(
            ts = ts,
            isScreenLocked = isScreenLocked,
        )

        withAuthRetry { token ->
            backendClient.postScreenState(token, body)
        }
    }

    suspend fun syncInstalledAppCatalog(
        ts: Long = System.currentTimeMillis(),
        forceUploadIcons: Boolean = false,
        onProgress: ((AppCatalogSyncProgress) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        onProgress?.invoke(
            AppCatalogSyncProgress(
                message = "Preparing installed apps...",
                totalApps = 0,
                syncedApps = 0,
                totalBatches = 0,
                syncedBatches = 0,
                appsWithIcons = null,
            ),
        )
        val apps = collectInstalledAppsForCatalogSync(
            forceUploadIcons = forceUploadIcons,
            onProgress = onProgress,
        )
        if (apps.isEmpty()) {
            Log.i(TAG, "APP_SYNC no installed apps to sync")
            onProgress?.invoke(
                AppCatalogSyncProgress(
                    message = "No installed apps found",
                    totalApps = 0,
                    syncedApps = 0,
                    totalBatches = 0,
                    syncedBatches = 0,
                    appsWithIcons = 0,
                ),
            )
            return@withContext
        }

        val batchedApps = chunkCatalogApps(apps)
        val appsWithIconPayload = apps.count { !it.iconBase64.isNullOrBlank() }
        Log.i(
            TAG,
            "APP_SYNC start totalApps=${apps.size} batches=${batchedApps.size} appsWithIconPayload=$appsWithIconPayload",
        )
        onProgress?.invoke(
            AppCatalogSyncProgress(
                message = "Collected ${apps.size} installed apps (${appsWithIconPayload} with icons), uploading...",
                totalApps = apps.size,
                syncedApps = 0,
                totalBatches = batchedApps.size,
                syncedBatches = 0,
                appsWithIcons = appsWithIconPayload,
            ),
        )

        var syncedApps = 0

        batchedApps.forEachIndexed { index, batch ->
            val batchNo = index + 1
            onProgress?.invoke(
                AppCatalogSyncProgress(
                    message = "Uploading batch $batchNo/${batchedApps.size} (${batch.size} apps)",
                    totalApps = apps.size,
                    syncedApps = syncedApps,
                    totalBatches = batchedApps.size,
                    syncedBatches = index,
                    appsWithIcons = appsWithIconPayload,
                ),
            )
            val body = AppCatalogSyncRequest(
                ts = ts,
                apps = batch,
            )
            withAuthRetry { token ->
                backendClient.postAppCatalogSync(token, body)
            }
            syncedApps += batch.size
            Log.i(TAG, "APP_SYNC batch=${index + 1}/${batchedApps.size} syncedApps=${batch.size}")
            onProgress?.invoke(
                AppCatalogSyncProgress(
                    message = "Uploaded batch $batchNo/${batchedApps.size}",
                    totalApps = apps.size,
                    syncedApps = syncedApps,
                    totalBatches = batchedApps.size,
                    syncedBatches = batchNo,
                    appsWithIcons = appsWithIconPayload,
                ),
            )
        }

        onProgress?.invoke(
            AppCatalogSyncProgress(
                message = "Sync completed",
                totalApps = apps.size,
                syncedApps = apps.size,
                totalBatches = batchedApps.size,
                syncedBatches = batchedApps.size,
                appsWithIcons = appsWithIconPayload,
            ),
        )
    }

    fun incrementNotificationCount() {
        counterStore.incrementNotification()
    }

    fun incrementUnlockCount() {
        counterStore.incrementUnlock()
    }

    fun currentWsUrl(): String? = sessionStore.wsUrl

    fun currentAccessToken(): String? = sessionStore.accessToken

    private fun hasSession(): Boolean {
        return !sessionStore.deviceId.isNullOrBlank() &&
            !sessionStore.accessToken.isNullOrBlank() &&
            !sessionStore.wsUrl.isNullOrBlank()
    }

    private fun buildRegisterRequest(): RegisterDeviceRequest {
        return RegisterDeviceRequest(
            deviceCode = resolveDeviceCode(),
            deviceName = Build.MODEL ?: "Android",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            appVersion = BuildConfig.VERSION_NAME,
            rootEnabled = RootUtils.hasRoot(),
        )
    }

    private fun registerDeviceLocked() {
        val response = backendClient.registerDevice(buildRegisterRequest())
        sessionStore.deviceId = response.deviceId
        sessionStore.accessToken = response.accessToken
        sessionStore.refreshToken = response.refreshToken
        sessionStore.wsUrl = response.wsUrl
    }

    private suspend fun <T> withAuthRetry(block: (String) -> T): T {
        ensureRegistered()
        val firstToken = sessionStore.accessToken ?: error("Missing access token")

        return try {
            block(firstToken)
        } catch (error: BackendHttpException) {
            if (error.statusCode != 401) {
                throw error
            }

            refreshSessionAfterUnauthorized(firstToken)
            val retryToken = sessionStore.accessToken ?: error("Missing access token after re-register")
            block(retryToken)
        }
    }

    private suspend fun refreshSessionAfterUnauthorized(failedToken: String) {
        registrationMutex.withLock {
            val currentToken = sessionStore.accessToken
            if (!currentToken.isNullOrBlank() && currentToken != failedToken) {
                return@withLock
            }

            sessionStore.clearSession()
            registerDeviceLocked()
        }
    }

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

    private fun resolveAppIcon(
        packageName: String,
        apkPath: String? = null,
        maxIconSizePx: Int? = null,
    ): ByteArray? {
        val drawable = runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: resolveIconFromApkPath(apkPath) ?: return null

        return drawable.toPngBytes(maxIconSizePx = maxIconSizePx)
    }

    private fun resolveIconFromApkPath(apkPath: String?): Drawable? {
        if (apkPath.isNullOrBlank()) {
            return null
        }
        val archiveAppInfo = resolveArchiveApplicationInfo(apkPath) ?: return null
        return runCatching {
            archiveAppInfo.loadIcon(packageManager)
        }.getOrNull()
    }

    private fun collectInstalledAppsForCatalogSync(
        forceUploadIcons: Boolean,
        onProgress: ((AppCatalogSyncProgress) -> Unit)? = null,
    ): List<AppCatalogItemRequest> {
        val installedApps = queryInstalledApps(onProgress = onProgress)
        if (installedApps.isEmpty()) {
            return emptyList()
        }

        var iconResolveFailureCount = 0
        val iconResolveFailureSamples = mutableListOf<String>()

        val appCatalogItems = installedApps.map { app ->
            val iconBytes = resolveAppIcon(
                packageName = app.packageName,
                apkPath = app.apkPath,
                maxIconSizePx = APP_SYNC_ICON_SIZE_PX,
            )
            if (forceUploadIcons && iconBytes == null) {
                iconResolveFailureCount += 1
                if (iconResolveFailureSamples.size < APP_SYNC_ICON_FAILURE_SAMPLE_LIMIT) {
                    iconResolveFailureSamples += app.packageName
                }
            }
            val iconHash = iconBytes?.let(::md5Hex)
            val shouldUploadIcon = when {
                iconHash.isNullOrBlank() -> false
                forceUploadIcons -> true
                else -> iconCacheStore.shouldUploadIcon(app.packageName, iconHash)
            }
            val iconBase64 = if (shouldUploadIcon) {
                Base64.encodeToString(checkNotNull(iconBytes), Base64.NO_WRAP)
            } else {
                null
            }

            if (shouldUploadIcon && !iconHash.isNullOrBlank()) {
                iconCacheStore.saveIconHash(app.packageName, iconHash)
            }

            AppCatalogItemRequest(
                packageName = app.packageName,
                appName = app.appName,
                iconHash = iconHash,
                iconBase64 = iconBase64,
            )
        }

        if (forceUploadIcons) {
            if (iconResolveFailureCount > 0) {
                Log.w(
                    TAG,
                    "APP_SYNC icon resolve failed count=$iconResolveFailureCount samplePackages=${iconResolveFailureSamples.joinToString(",")}",
                )
            } else {
                Log.i(TAG, "APP_SYNC icon resolve failed count=0 totalApps=${installedApps.size}")
            }
        }

        return appCatalogItems
    }

    private fun queryInstalledApps(
        onProgress: ((AppCatalogSyncProgress) -> Unit)? = null,
    ): List<InstalledAppInfo> {
        val packageManagerApps = queryInstalledAppsViaPackageManager()

        if (RootUtils.hasRoot()) {
            onProgress?.invoke(
                AppCatalogSyncProgress(
                    message = "Using ROOT package scan to supplement package manager...",
                    totalApps = 0,
                    syncedApps = 0,
                    totalBatches = 0,
                    syncedBatches = 0,
                ),
            )

            val rootApps = queryInstalledAppsViaRoot()
            if (rootApps.isEmpty()) {
                onProgress?.invoke(
                    AppCatalogSyncProgress(
                        message = "ROOT scan unavailable, using package manager scan only",
                        totalApps = 0,
                        syncedApps = 0,
                        totalBatches = 0,
                        syncedBatches = 0,
                    ),
                )
                return packageManagerApps
            }

            val mergedApps = mergeInstalledAppsForCatalogSync(
                primary = packageManagerApps,
                supplement = rootApps,
            )
            Log.i(
                TAG,
                "APP_SYNC app source packageManager=${packageManagerApps.size} root=${rootApps.size} merged=${mergedApps.size}",
            )
            return mergedApps
        } else {
            onProgress?.invoke(
                AppCatalogSyncProgress(
                    message = "ROOT unavailable, using permission-based scan",
                    totalApps = 0,
                    syncedApps = 0,
                    totalBatches = 0,
                    syncedBatches = 0,
                ),
            )
        }

        return packageManagerApps
    }

    private fun queryInstalledAppsViaPackageManager(): List<InstalledAppInfo> {
        val appInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return appInfos
            .asSequence()
            .filter { info ->
                info.packageName.isNotBlank() &&
                    info.packageName != context.packageName &&
                    (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            }
            .distinctBy { info -> info.packageName }
            .map { info ->
                val isSystemApp =
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                InstalledAppInfo(
                    packageName = info.packageName,
                    appName = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
                    isSystemApp = isSystemApp,
                    apkPath = info.publicSourceDir ?: info.sourceDir,
                )
            }
            .sortedWith(
                compareBy<InstalledAppInfo> { !it.isSystemApp }
                    .thenBy { it.appName.lowercase(Locale.ROOT) },
            )
            .toList()
    }

    private fun queryInstalledAppsViaRoot(): List<InstalledAppInfo> {
        val result = RootUtils.runSuCommandForOutput(
            command = "pm list packages -f --user 0",
            timeoutMs = ROOT_PACKAGE_LIST_TIMEOUT_MS,
        )
        if (result.timedOut || result.exitCode != 0 || result.stdout.isBlank()) {
            Log.w(
                TAG,
                "APP_SYNC root package scan failed exit=${result.exitCode} timedOut=${result.timedOut} stderr=${result.stderr}",
            )
            return emptyList()
        }

        val rootEntries = parseRootPackageEntriesFromPmList(result.stdout)
        if (rootEntries.isEmpty()) {
            return emptyList()
        }

        return rootEntries
            .asSequence()
            .filter { entry ->
                entry.packageName.isNotBlank() && entry.packageName != context.packageName
            }
            .mapNotNull { entry ->
                val appInfo = runCatching {
                    packageManager.getApplicationInfo(entry.packageName, 0)
                }.getOrNull()
                val archiveAppInfo = if (appInfo == null) {
                    resolveArchiveApplicationInfo(entry.apkPath)
                } else {
                    null
                }
                val sourceInfo = appInfo ?: archiveAppInfo ?: return@mapNotNull null
                val appName = runCatching {
                    packageManager.getApplicationLabel(sourceInfo).toString()
                }.getOrNull().orEmpty().ifBlank { entry.packageName }

                val isSystemApp = if (appInfo != null) {
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } else {
                    isSystemInstallPath(entry.apkPath)
                }

                InstalledAppInfo(
                    packageName = entry.packageName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    apkPath = entry.apkPath,
                )
            }
            .distinctBy { app -> app.packageName }
            .sortedWith(
                compareBy<InstalledAppInfo> { it.isSystemApp }
                    .thenBy { it.appName.lowercase(Locale.ROOT) },
            )
            .toList()
    }

    private fun resolveArchiveApplicationInfo(apkPath: String?): ApplicationInfo? {
        if (apkPath.isNullOrBlank()) {
            return null
        }

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkPath, 0)
        } ?: return null

        val appInfo = packageInfo.applicationInfo ?: return null
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        return appInfo
    }

    private fun isSystemInstallPath(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }
        return SYSTEM_APP_PATH_PREFIXES.any { prefix ->
            path.startsWith(prefix)
        }
    }

    private fun chunkCatalogApps(apps: List<AppCatalogItemRequest>): List<List<AppCatalogItemRequest>> {
        if (apps.isEmpty()) {
            return emptyList()
        }

        val batches = mutableListOf<MutableList<AppCatalogItemRequest>>()
        var currentBatch = mutableListOf<AppCatalogItemRequest>()
        var currentSize = 0

        apps.forEach { app ->
            val estimatedSize = estimateCatalogItemPayloadSize(app)
            val exceedsItemCount = currentBatch.size >= APP_SYNC_MAX_BATCH_ITEMS
            val exceedsPayload =
                currentBatch.isNotEmpty() &&
                    currentSize + estimatedSize > APP_SYNC_MAX_BATCH_ESTIMATED_BYTES

            if (exceedsItemCount || exceedsPayload) {
                batches += currentBatch
                currentBatch = mutableListOf()
                currentSize = 0
            }

            currentBatch += app
            currentSize += estimatedSize
        }

        if (currentBatch.isNotEmpty()) {
            batches += currentBatch
        }

        return batches
    }

    private fun estimateCatalogItemPayloadSize(app: AppCatalogItemRequest): Int {
        return app.packageName.length +
            app.appName.length +
            (app.iconHash?.length ?: 0) +
            (app.iconBase64?.length ?: 0) +
            APP_SYNC_BATCH_ITEM_OVERHEAD
    }

    private fun Drawable.toPngBytes(maxIconSizePx: Int? = null): ByteArray? {
        val sourceWidth = intrinsicWidth.coerceAtLeast(1)
        val sourceHeight = intrinsicHeight.coerceAtLeast(1)
        val maxSize = maxIconSizePx?.coerceAtLeast(1)
        val scale = if (maxSize == null) {
            1f
        } else {
            minOf(1f, maxSize.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat())
        }
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            targetWidth,
            targetHeight,
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

    companion object {
        private const val TAG = "TelemetryManager"
        private const val APP_SYNC_ICON_SIZE_PX = 72
        private const val APP_SYNC_MAX_BATCH_ITEMS = 4
        private const val APP_SYNC_MAX_BATCH_ESTIMATED_BYTES = 48_000
        private const val APP_SYNC_BATCH_ITEM_OVERHEAD = 1024
        private const val APP_SYNC_ICON_FAILURE_SAMPLE_LIMIT = 12
        private const val ROOT_PACKAGE_LIST_TIMEOUT_MS = 8_000L
        private val SYSTEM_APP_PATH_PREFIXES = listOf(
            "/system/",
            "/product/",
            "/system_ext/",
            "/vendor/",
            "/apex/",
            "/odm/",
        )
    }
}
