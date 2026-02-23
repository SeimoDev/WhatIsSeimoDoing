package com.whatisseimo.doing.core

import io.socket.client.IO
import io.socket.client.Socket

class DeviceSocketClient {
    private var socket: Socket? = null

    fun connect(wsUrl: String, accessToken: String, onScreenshotRequest: (String) -> Unit) {
        if (socket?.connected() == true) {
            return
        }

        val options = IO.Options()
        options.reconnection = true
        options.forceNew = true
        options.transports = arrayOf("websocket")
        options.query = "clientType=device&token=$accessToken"
        options.auth = mapOf(
            "clientType" to "device",
            "token" to accessToken,
        )

        socket = IO.socket(wsUrl, options).apply {
            on("screenshot.request") { args ->
                val first = args.firstOrNull() as? org.json.JSONObject ?: return@on
                val requestId = first.optString("requestId")
                if (requestId.isNotBlank()) {
                    onScreenshotRequest(requestId)
                }
            }
            connect()
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
