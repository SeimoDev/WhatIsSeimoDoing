package com.whatisseimo.doing.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatisseimo.doing.WhatIsSeimoDoingApp
import com.whatisseimo.doing.core.AppCatalogSyncProgress
import com.whatisseimo.doing.core.KeepAliveService
import com.whatisseimo.doing.core.SocketReconnectEvent
import com.whatisseimo.doing.core.SocketReconnectEventBus
import com.whatisseimo.doing.util.PermissionHelper
import com.whatisseimo.doing.util.RootUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
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

data class AppCatalogSyncUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val progress: Float? = null,
    val progressText: String = "",
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class SocketReconnectUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val statusText: String = "",
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class MainViewModel : ViewModel() {
    private val _snapshot = MutableStateFlow(PermissionSnapshot())
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    private val _appCatalogSyncState = MutableStateFlow(AppCatalogSyncUiState())
    val appCatalogSyncState: StateFlow<AppCatalogSyncUiState> = _appCatalogSyncState.asStateFlow()

    private val _socketReconnectState = MutableStateFlow(SocketReconnectUiState())
    val socketReconnectState: StateFlow<SocketReconnectUiState> = _socketReconnectState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var activeReconnectSessionId: String? = null
    private var reconnectTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            SocketReconnectEventBus.events.collect { event ->
                onSocketReconnectEvent(event)
            }
        }
    }

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

    fun reconnectSocket(context: Context) {
        val sessionId = UUID.randomUUID().toString()
        activeReconnectSessionId = sessionId
        reconnectTimeoutJob?.cancel()

        _socketReconnectState.value = SocketReconnectUiState(
            visible = true,
            running = true,
            statusText = "Reconnecting...",
            logs = listOf(formatLogLine("Reconnect requested")),
        )

        KeepAliveService.reconnectSocket(context, sessionId)
        reconnectTimeoutJob = viewModelScope.launch {
            delay(RECONNECT_TIMEOUT_MS)
            if (activeReconnectSessionId == sessionId && _socketReconnectState.value.running) {
                _socketReconnectState.update { state ->
                    state.copy(
                        running = false,
                        statusText = "Reconnect timed out",
                        errorMessage = "Reconnect timed out",
                        logs = appendLog(
                            existing = state.logs,
                            line = formatLogLine("Reconnect timed out"),
                            maxLines = MAX_RECONNECT_LOG_LINES,
                        ),
                    )
                }
                activeReconnectSessionId = null
            }
        }
    }

    fun startAppCatalogSync(context: Context) {
        if (_appCatalogSyncState.value.running) {
            return
        }

        _appCatalogSyncState.value = AppCatalogSyncUiState(
            visible = true,
            running = true,
            progress = null,
            progressText = "Preparing...",
            logs = listOf("Sync started"),
        )

        val app = context.applicationContext as? WhatIsSeimoDoingApp
        if (app == null) {
            _appCatalogSyncState.update { state ->
                state.copy(
                    running = false,
                    errorMessage = "Application context unavailable",
                    progressText = "Sync failed",
                    logs = appendLog(state.logs, "Sync failed: application context unavailable"),
                )
            }
            return
        }

        viewModelScope.launch {
            runCatching {
                app.graph.telemetryManager.syncInstalledAppCatalog(
                    forceUploadIcons = true,
                    onProgress = { progress ->
                        onAppCatalogSyncProgress(progress)
                    },
                )
            }.onSuccess {
                _appCatalogSyncState.update { state ->
                    state.copy(
                        running = false,
                        progress = 1f,
                        progressText = state.progressText.ifBlank { "Completed" },
                        logs = appendLog(state.logs, "Sync completed"),
                    )
                }
            }.onFailure { error ->
                _appCatalogSyncState.update { state ->
                    state.copy(
                        running = false,
                        errorMessage = error.message ?: "Unknown error",
                        progressText = "Sync failed",
                        logs = appendLog(state.logs, "Sync failed: ${error.message ?: "unknown"}"),
                    )
                }
            }
        }
    }

    fun dismissAppCatalogSyncDialog() {
        if (_appCatalogSyncState.value.running) {
            return
        }
        _appCatalogSyncState.value = AppCatalogSyncUiState()
    }

    fun dismissSocketReconnectDialog() {
        if (_socketReconnectState.value.running) {
            return
        }
        _socketReconnectState.value = SocketReconnectUiState()
    }

    private fun onAppCatalogSyncProgress(progress: AppCatalogSyncProgress) {
        val fractionalProgress = if (progress.totalBatches > 0) {
            progress.syncedBatches.toFloat() / progress.totalBatches.toFloat()
        } else {
            null
        }

        val detailParts = mutableListOf<String>()
        if (progress.totalApps > 0) {
            detailParts += "${progress.syncedApps}/${progress.totalApps} apps"
        }
        progress.appsWithIcons?.let { icons ->
            detailParts += "$icons with icons"
        }
        val detail = detailParts.joinToString(" | ")
        val collectedSummary =
            if (progress.totalApps > 0 && progress.appsWithIcons != null && progress.totalBatches > 0) {
                "Collected ${progress.totalApps} installed apps (${progress.appsWithIcons} with icons), uploading..."
            } else {
                null
            }

        _appCatalogSyncState.update { state ->
            val nextLogs = appendLog(state.logs, progress.message)
            val logsWithSummary = if (collectedSummary != null && !nextLogs.contains(collectedSummary)) {
                appendLog(nextLogs, collectedSummary)
            } else {
                nextLogs
            }
            state.copy(
                progress = fractionalProgress,
                progressText = listOf(progress.message, detail)
                    .filter { it.isNotBlank() }
                    .joinToString(" | "),
                logs = logsWithSummary,
                errorMessage = null,
            )
        }
    }

    private fun onSocketReconnectEvent(event: SocketReconnectEvent) {
        if (event.sessionId != activeReconnectSessionId) {
            return
        }

        _socketReconnectState.update { state ->
            val isFailure = event.isTerminal && event.isSuccess == false
            val isSuccess = event.isTerminal && event.isSuccess == true
            state.copy(
                running = !event.isTerminal,
                statusText = when {
                    isSuccess -> "Connected"
                    isFailure -> "Reconnect failed"
                    else -> event.message
                },
                logs = appendLog(
                    existing = state.logs,
                    line = formatLogLine(event.message, event.timestampMs),
                    maxLines = MAX_RECONNECT_LOG_LINES,
                ),
                errorMessage = if (isFailure) event.message else null,
            )
        }

        if (event.isTerminal) {
            reconnectTimeoutJob?.cancel()
            reconnectTimeoutJob = null
            activeReconnectSessionId = null
        }
    }

    private fun formatLogLine(message: String, timestampMs: Long = System.currentTimeMillis()): String {
        val timestamp = synchronized(timeFormatter) {
            timeFormatter.format(Date(timestampMs))
        }
        return "[$timestamp] $message"
    }

    private fun appendLog(existing: List<String>, line: String, maxLines: Int = MAX_SYNC_LOG_LINES): List<String> {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return existing
        }
        val next = existing + trimmed
        return if (next.size > maxLines) {
            next.takeLast(maxLines)
        } else {
            next
        }
    }

    override fun onCleared() {
        reconnectTimeoutJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_SYNC_LOG_LINES = 120
        private const val MAX_RECONNECT_LOG_LINES = 120
        private const val RECONNECT_TIMEOUT_MS = 30_000L
    }
}
