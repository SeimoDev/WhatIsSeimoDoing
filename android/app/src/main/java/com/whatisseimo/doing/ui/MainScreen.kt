package com.whatisseimo.doing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whatisseimo.doing.util.PermissionHelper

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val syncState by viewModel.appCatalogSyncState.collectAsState()
    val reconnectState by viewModel.socketReconnectState.collectAsState()

    val entries = listOf(
        PermissionItem(
            title = "Accessibility (Fallback)",
            granted = snapshot.accessibilityEnabled,
            action = { PermissionHelper.openAccessibilitySettings(context) },
        ),
        PermissionItem(
            title = "Usage Access",
            granted = snapshot.usageAccessEnabled,
            action = { PermissionHelper.openUsageAccessSettings(context) },
        ),
        PermissionItem(
            title = "Notification Listener",
            granted = snapshot.notificationListenerEnabled,
            action = { PermissionHelper.openNotificationListenerSettings(context) },
        ),
        PermissionItem(
            title = "Battery Optimization",
            granted = snapshot.batteryOptimizationIgnored,
            action = { PermissionHelper.openBatteryOptimizationSettings(context) },
        ),
        PermissionItem(
            title = "Overlay Permission",
            granted = snapshot.overlayEnabled,
            action = { PermissionHelper.openOverlaySettings(context) },
        ),
        PermissionItem(
            title = "MIUI AutoStart",
            granted = snapshot.miuiAutoStartEnabled,
            action = { PermissionHelper.openMiuiAutostartSettings(context) },
        ),
        PermissionItem(
            title = "Root Available",
            granted = snapshot.rootAvailable,
            action = {},
            canOpen = false,
        ),
    )

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "WhatIsSeimoDoing Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Server: http://192.168.2.247:3030",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (snapshot.rootAvailable) {
                Text(
                    text = "ROOT已接管前台识别，无障碍可选",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF0E8A66),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.startKeepAlive(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start KeepAlive")
                }
                Button(
                    onClick = { viewModel.reconnectSocket(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reconnect Socket")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.startAppCatalogSync(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !syncState.running,
                ) {
                    Text("Sync Apps")
                }
                Button(
                    onClick = { viewModel.refresh(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Refresh State")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRequestNotificationPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Notif Perm")
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries) { item ->
                    PermissionCard(item)
                }
            }
        }
    }

    if (syncState.visible) {
        AppCatalogSyncDialog(
            state = syncState,
            onDismissRequest = { viewModel.dismissAppCatalogSyncDialog() },
        )
    }

    if (reconnectState.visible) {
        SocketReconnectDialog(
            state = reconnectState,
            onDismissRequest = { viewModel.dismissSocketReconnectDialog() },
        )
    }
}

private data class PermissionItem(
    val title: String,
    val granted: Boolean?,
    val action: () -> Unit,
    val canOpen: Boolean = true,
)

@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                val (text, color) = when (item.granted) {
                    true -> "Enabled" to Color(0xFF0E8A66)
                    false -> "Not Enabled" to Color(0xFFB23C2B)
                    null -> "Unknown (MIUI restricted)" to Color(0xFF9C6A09)
                }
                Text(
                    text = text,
                    color = color,
                )
            }

            if (item.canOpen) {
                Button(onClick = item.action) {
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun AppCatalogSyncDialog(
    state: AppCatalogSyncUiState,
    onDismissRequest: () -> Unit,
) {
    val logScrollState = rememberScrollState()

    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    AlertDialog(
        onDismissRequest = {
            if (!state.running) {
                onDismissRequest()
            }
        },
        title = {
            Text("Sync Apps")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.progressText.isNotBlank()) {
                    Text(
                        text = state.progressText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "Error: ${state.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB23C2B),
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScrollState)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.logs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.running,
                onClick = onDismissRequest,
            ) {
                Text(if (state.running) "Syncing..." else "Close")
            }
        },
    )
}

@Composable
private fun SocketReconnectDialog(
    state: SocketReconnectUiState,
    onDismissRequest: () -> Unit,
) {
    val logScrollState = rememberScrollState()

    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    AlertDialog(
        onDismissRequest = {
            if (!state.running) {
                onDismissRequest()
            }
        },
        title = {
            Text("Reconnect Socket")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (state.statusText.isNotBlank()) {
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "Error: ${state.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB23C2B),
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScrollState)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.logs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.running,
                onClick = onDismissRequest,
            ) {
                Text(if (state.running) "Reconnecting..." else "Close")
            }
        },
    )
}
