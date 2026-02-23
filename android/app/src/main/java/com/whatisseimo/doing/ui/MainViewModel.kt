package com.whatisseimo.doing.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatisseimo.doing.core.KeepAliveService
import com.whatisseimo.doing.util.PermissionHelper
import com.whatisseimo.doing.util.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PermissionSnapshot(
    val accessibilityEnabled: Boolean = false,
    val usageAccessEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val overlayEnabled: Boolean = false,
    val miuiAutoStartEnabled: Boolean? = null,
    val rootAvailable: Boolean = false,
)

class MainViewModel : ViewModel() {
    private val _snapshot = MutableStateFlow(PermissionSnapshot())
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    fun refresh(context: Context) {
        viewModelScope.launch {
            _snapshot.value = PermissionSnapshot(
                accessibilityEnabled = PermissionHelper.isAccessibilityEnabled(context),
                usageAccessEnabled = PermissionHelper.hasUsageStatsPermission(context),
                notificationListenerEnabled = PermissionHelper.isNotificationListenerEnabled(context),
                batteryOptimizationIgnored = PermissionHelper.isIgnoringBatteryOptimization(context),
                overlayEnabled = PermissionHelper.canDrawOverlays(context),
                miuiAutoStartEnabled = PermissionHelper.queryMiuiAutoStartEnabled(context),
                rootAvailable = RootUtils.hasRoot(),
            )
        }
    }

    fun startKeepAlive(context: Context) {
        KeepAliveService.start(context)
    }
}
