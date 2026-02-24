package com.whatisseimo.doing.core

import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket

class DeviceSocketClient {
    private var socket: Socket? = null
    private var lastWsUrl: String? = null
    private var lastAccessToken: String? = null
    private var screenshotRequestHandler: ((String) -> Unit)? = null
    private var connectionEventHandler: ((String, String?) -> Unit)? = null
    private val connectionState = SocketConnectionState()
    private var manualDisconnecting = false

    @Synchronized
    fun connectIfNeeded(
        wsUrl: String,
        accessToken: String,
        onScreenshotRequest: (String) -> Unit,
        onConnectionEvent: (String, String?) -> Unit = { _, _ -> },
    ) {
        screenshotRequestHandler = onScreenshotRequest
        connectionEventHandler = onConnectionEvent

        val needsRebuild = wsUrl != lastWsUrl || accessToken != lastAccessToken
        if (needsRebuild) {
            replaceSocket(wsUrl, accessToken)
        } else if (socket == null) {
            connectionState.reset()
            createSocket(wsUrl, accessToken)
        }

        if (connectionState.isConnected || connectionState.isConnecting) {
            return
        }

        connectionState.onConnectRequested()
        socket?.connect()
    }

    @Synchronized
    fun forceReconnect() {
        val wsUrl = lastWsUrl ?: return
        val accessToken = lastAccessToken ?: return
        replaceSocket(wsUrl, accessToken)
        connectionState.onConnectRequested()
        socket?.connect()
    }

    @Synchronized
    fun isConnected(): Boolean = connectionState.isConnected

    @Synchronized
    fun isConnecting(): Boolean = connectionState.isConnecting

    @Synchronized
    fun disconnect() {
        connectionState.reset()
        disposeCurrentSocket()
        lastWsUrl = null
        lastAccessToken = null
        screenshotRequestHandler = null
        connectionEventHandler = null
    }

    @Synchronized
    private fun replaceSocket(wsUrl: String, accessToken: String) {
        connectionState.reset()
        disposeCurrentSocket()
        createSocket(wsUrl, accessToken)
    }

    @Synchronized
    private fun createSocket(wsUrl: String, accessToken: String) {
        lastWsUrl = wsUrl
        lastAccessToken = accessToken

        val options = IO.Options()
        options.reconnection = true
        options.reconnectionAttempts = Int.MAX_VALUE
        options.reconnectionDelay = 1_000
        options.reconnectionDelayMax = 20_000
        options.transports = arrayOf("websocket")
        options.query = "clientType=device&token=$accessToken"
        options.auth = mapOf(
            "clientType" to "device",
            "token" to accessToken,
        )

        socket = IO.socket(wsUrl, options).apply {
            on(Socket.EVENT_CONNECT) {
                synchronized(this@DeviceSocketClient) {
                    connectionState.onConnected()
                    emitConnectionEvent(EVENT_CONNECT, null)
                }
            }
            on(Socket.EVENT_DISCONNECT) { args ->
                synchronized(this@DeviceSocketClient) {
                    connectionState.onDisconnected(willReconnect = !manualDisconnecting)
                    emitConnectionEvent(EVENT_DISCONNECT, parseDetail(args))
                }
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                synchronized(this@DeviceSocketClient) {
                    connectionState.onConnectError()
                    emitConnectionEvent(EVENT_CONNECT_ERROR, parseDetail(args))
                }
            }
            on(Manager.EVENT_RECONNECT_ATTEMPT) { args ->
                synchronized(this@DeviceSocketClient) {
                    connectionState.onReconnectAttempt()
                    emitConnectionEvent(EVENT_RECONNECT_ATTEMPT, parseDetail(args))
                }
            }
            on(Manager.EVENT_RECONNECT_FAILED) {
                synchronized(this@DeviceSocketClient) {
                    connectionState.onReconnectFailed()
                    emitConnectionEvent(EVENT_RECONNECT_FAILED, null)
                }
            }
            on("screenshot.request") { args ->
                val first = args.firstOrNull() as? org.json.JSONObject ?: return@on
                val requestId = first.optString("requestId")
                if (requestId.isNotBlank()) {
                    screenshotRequestHandler?.invoke(requestId)
                }
            }
        }
    }

    @Synchronized
    private fun disposeCurrentSocket() {
        val current = socket ?: return
        manualDisconnecting = true
        current.disconnect()
        current.off()
        manualDisconnecting = false
        socket = null
    }

    private fun parseDetail(args: Array<Any>): String? {
        val first = args.firstOrNull() ?: return null
        return when (first) {
            is Throwable -> first.message
            else -> first.toString()
        }?.takeIf { it.isNotBlank() }
    }

    private fun emitConnectionEvent(event: String, detail: String?) {
        connectionEventHandler?.invoke(event, detail)
    }

    companion object {
        const val EVENT_CONNECT = "EVENT_CONNECT"
        const val EVENT_DISCONNECT = "EVENT_DISCONNECT"
        const val EVENT_CONNECT_ERROR = "EVENT_CONNECT_ERROR"
        const val EVENT_RECONNECT_ATTEMPT = "EVENT_RECONNECT_ATTEMPT"
        const val EVENT_RECONNECT_FAILED = "EVENT_RECONNECT_FAILED"
    }
}

internal class SocketConnectionState {
    var isConnected: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set

    fun onConnectRequested() {
        isConnecting = true
    }

    fun onConnected() {
        isConnected = true
        isConnecting = false
    }

    fun onDisconnected(willReconnect: Boolean) {
        isConnected = false
        isConnecting = willReconnect
    }

    fun onConnectError() {
        isConnected = false
        isConnecting = false
    }

    fun onReconnectAttempt() {
        isConnecting = true
    }

    fun onReconnectFailed() {
        isConnecting = false
    }

    fun reset() {
        isConnected = false
        isConnecting = false
    }
}
