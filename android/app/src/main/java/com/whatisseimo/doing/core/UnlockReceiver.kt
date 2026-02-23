package com.whatisseimo.doing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whatisseimo.doing.WhatIsSeimoDoingApp

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) {
            return
        }

        val app = context.applicationContext as WhatIsSeimoDoingApp
        app.graph.telemetryManager.incrementUnlockCount()
        KeepAliveService.start(context)
    }
}
