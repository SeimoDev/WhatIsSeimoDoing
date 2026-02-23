package com.whatisseimo.doing.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.whatisseimo.doing.WhatIsSeimoDoingApp

class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) {
            return
        }

        val telemetry = (application as WhatIsSeimoDoingApp).graph.telemetryManager
        telemetry.incrementNotificationCount()
    }
}
