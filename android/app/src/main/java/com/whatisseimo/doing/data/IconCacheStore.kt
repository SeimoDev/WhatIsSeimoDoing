package com.whatisseimo.doing.data

import android.content.Context
import android.content.SharedPreferences

class IconCacheStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("icon_cache", Context.MODE_PRIVATE)

    fun shouldUploadIcon(packageName: String, hash: String): Boolean {
        val key = "hash:$packageName"
        val previous = prefs.getString(key, null)
        return previous == null || previous != hash
    }

    fun getCachedIconHash(packageName: String): String? {
        return prefs.getString("hash:$packageName", null)
    }

    fun saveIconHash(packageName: String, hash: String) {
        prefs.edit().putString("hash:$packageName", hash).apply()
    }
}
