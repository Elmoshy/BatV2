package com.bat.v2

import android.app.Application

class BatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.install(this)
        Guard.install(this)
    }
}
