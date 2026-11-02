package com.nova.upload

import android.app.Application

class NovaUploadApp : Application() {
    lateinit var session: SessionStore
        private set
    lateinit var api: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        api = ApiClient()
    }
}

fun Application.nova(): NovaUploadApp = this as NovaUploadApp
