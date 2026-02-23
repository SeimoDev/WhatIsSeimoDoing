package com.whatisseimo.doing.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val deviceCode: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val rootEnabled: Boolean,
)

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val wsUrl: String,
    val heartbeatIntervalSec: Int,
)

@Serializable
data class HeartbeatRequest(
    val ts: Long,
    val batteryPct: Int,
    val isCharging: Boolean,
    val networkType: String,
)

@Serializable
data class ForegroundSwitchRequest(
    val ts: Long,
    val packageName: String,
    val appName: String,
    val iconHash: String? = null,
    val iconBase64: String? = null,
    val todayUsageMsAtSwitch: Long,
)

@Serializable
data class DailySnapshotAppItem(
    val packageName: String,
    val usageMsToday: Long,
)

@Serializable
data class DailySnapshotRequest(
    val ts: Long,
    val timezone: String = "Asia/Shanghai",
    val totalNotificationCount: Int,
    val unlockCount: Int,
    val apps: List<DailySnapshotAppItem>,
)

@Serializable
data class GenericOkResponse(
    val ok: Boolean = true,
)

@Serializable
sealed class QueuePayload {
    @Serializable
    @SerialName("foreground")
    data class Foreground(val body: ForegroundSwitchRequest) : QueuePayload()

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val body: DailySnapshotRequest) : QueuePayload()
}

@Serializable
data class QueuedEvent(
    val id: String,
    val createdAt: Long,
    val payload: QueuePayload,
)
