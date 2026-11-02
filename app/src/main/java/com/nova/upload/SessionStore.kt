package com.nova.upload

import android.content.Context

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_upload_session", Context.MODE_PRIVATE)

    var appToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var lastCategoryId: Int
        get() = prefs.getInt(KEY_CAT, 0)
        set(value) = prefs.edit().putInt(KEY_CAT, value).apply()

    fun isLoggedIn(): Boolean = !appToken.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "app_token"
        private const val KEY_NAME = "display_name"
        private const val KEY_CAT = "last_cat"
    }
}
