package com.whatisseimo.doing.data

import android.content.Context
import android.content.SharedPreferences

class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("session_store", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var wsUrl: String?
        get() = prefs.getString("ws_url", null)
        set(value) = prefs.edit().putString("ws_url", value).apply()

    fun clearSession() {
        prefs.edit()
            .remove("device_id")
            .remove("access_token")
            .remove("refresh_token")
            .remove("ws_url")
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
