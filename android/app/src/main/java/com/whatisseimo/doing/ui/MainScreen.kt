package com.whatisseimo.doing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    val entries = listOf(
        PermissionItem(
            title = "Accessibility",
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.startKeepAlive(context) }) {
                    Text("Start KeepAlive")
                }
                Button(onClick = { viewModel.refresh(context) }) {
                    Text("Refresh State")
                }
                Button(onClick = onRequestNotificationPermission) {
                    Text("Notif Perm")
                }
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
