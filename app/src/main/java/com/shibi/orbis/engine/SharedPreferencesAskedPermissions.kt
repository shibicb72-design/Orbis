package com.shibi.orbis.engine

import android.content.SharedPreferences

class SharedPreferencesAskedPermissions(private val prefs: SharedPreferences) : AskedPermissions {
    override fun wasAsked(permission: String): Boolean = prefs.getBoolean(permission, false)

    override fun markAsked(permission: String) {
        prefs.edit().putBoolean(permission, true).apply()
    }

    companion object {
        const val FILE_NAME = "android_permissions_asked"
    }
}
