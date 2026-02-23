package com.whatisseimo.doing.core

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.whatisseimo.doing.WhatIsSeimoDoingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MonitorAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var telemetryManager: TelemetryManager

    private val overlayPermissionCache = mutableMapOf<String, Boolean>()
    private val imePackagesCache = mutableSetOf<String>()
    private var imeCacheUpdatedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        telemetryManager = (application as WhatIsSeimoDoingApp).graph.telemetryManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        if (
            currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val packageName = currentEvent.packageName?.toString() ?: return
        if (packageName == this.packageName) {
            return
        }
        if (shouldIgnoreForegroundEvent(currentEvent, packageName)) {
            return
        }

        synchronized(lastPackageLock) {
            if (lastPackageName == packageName) {
                return
            }
            lastPackageName = packageName
        }

        serviceScope.launch {
            runCatching {
                telemetryManager.reportForegroundSwitch(packageName = packageName)
            }.onFailure {
                Log.e(TAG, "Failed to report foreground switch", it)
            }
        }
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorA11yService"
        private const val SYSTEM_ALERT_WINDOW_PERMISSION = "android.permission.SYSTEM_ALERT_WINDOW"
        private const val IME_CACHE_TTL_MS = 5_000L

        private val TRANSIENT_SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.android.permissioncontroller",
        )

        private val TRANSIENT_CLASS_KEYWORDS = setOf(
            "statusbar",
            "notification",
            "headsup",
            "shade",
            "panel",
            "volume",
            "toast",
            "inputmethod",
            "softinput",
            "keyboard",
        )

        private var lastPackageName: String? = null
        private val lastPackageLock = Any()
    }

    private fun shouldIgnoreForegroundEvent(
        event: AccessibilityEvent,
        packageName: String,
    ): Boolean {
        val className = event.className?.toString().orEmpty()
        val classNameLower = className.lowercase()

        if (isInputMethodPackage(packageName) || isInputMethodClass(classNameLower)) {
            return true
        }

        if (packageName in TRANSIENT_SYSTEM_UI_PACKAGES) {
            if (classNameLower.isBlank()) {
                return true
            }
            return TRANSIENT_CLASS_KEYWORDS.any { keyword ->
                classNameLower.contains(keyword)
            }
        }

        // For overlay-enabled apps, ignore non-activity WINDOWS_CHANGED events
        // to avoid floating-window interference.
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            hasOverlayPermissionRequest(packageName) &&
            !looksLikeActivityWindow(className)
        ) {
            return true
        }

        return false
    }

    private fun isInputMethodClass(classNameLower: String): Boolean {
        if (classNameLower.isBlank()) {
            return false
        }
        return classNameLower.contains("inputmethod") ||
            classNameLower.contains("softinput") ||
            classNameLower.contains("keyboard")
    }

    private fun looksLikeActivityWindow(className: String): Boolean {
        if (className.isBlank()) {
            return false
        }
        return className.endsWith("Activity") ||
            className.contains("activity", ignoreCase = true)
    }

    private fun isInputMethodPackage(packageName: String): Boolean {
        refreshImePackagesCacheIfNeeded()
        return packageName in imePackagesCache
    }

    private fun refreshImePackagesCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (imePackagesCache.isNotEmpty() && now - imeCacheUpdatedAt < IME_CACHE_TTL_MS) {
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

    private fun hasOverlayPermissionRequest(packageName: String): Boolean {
        return overlayPermissionCache.getOrPut(packageName) {
            runCatching {
                val requestedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                    ).requestedPermissions
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                }
                requestedPermissions?.contains(SYSTEM_ALERT_WINDOW_PERMISSION) == true
            }.getOrDefault(false)
        }
    }
}
