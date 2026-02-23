package com.whatisseimo.doing.data

import android.content.Context
import com.whatisseimo.doing.core.TelemetryManager
import com.whatisseimo.doing.network.BackendClient

class ServiceGraph(context: Context) {
    private val appContext = context.applicationContext

    val sessionStore = SessionStore(appContext)
    val counterStore = CounterStore(appContext)
    val iconCacheStore = IconCacheStore(appContext)
    val queueStore = EventQueueStore(appContext)
    val backendClient = BackendClient()
    val telemetryManager = TelemetryManager(
        context = appContext,
        sessionStore = sessionStore,
        counterStore = counterStore,
        iconCacheStore = iconCacheStore,
        queueStore = queueStore,
        backendClient = backendClient,
    )
}
