package com.whatisseimo.doing.data

import android.content.Context
import android.content.SharedPreferences
import com.whatisseimo.doing.util.chinaDate

class CounterStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("counter_store", Context.MODE_PRIVATE)

    fun incrementNotification(): Int {
        rotateIfNeeded()
        val next = prefs.getInt("notifications", 0) + 1
        prefs.edit().putInt("notifications", next).apply()
        return next
    }

    fun incrementUnlock(): Int {
        rotateIfNeeded()
        val next = prefs.getInt("unlocks", 0) + 1
        prefs.edit().putInt("unlocks", next).apply()
        return next
    }

    fun currentNotificationCount(): Int {
        rotateIfNeeded()
        return prefs.getInt("notifications", 0)
    }

    fun currentUnlockCount(): Int {
        rotateIfNeeded()
        return prefs.getInt("unlocks", 0)
    }

    private fun rotateIfNeeded() {
        val today = chinaDate()
        val cached = prefs.getString("date", null)
        if (today != cached) {
            prefs.edit()
                .putString("date", today)
                .putInt("notifications", 0)
                .putInt("unlocks", 0)
                .apply()
        }
    }
}
