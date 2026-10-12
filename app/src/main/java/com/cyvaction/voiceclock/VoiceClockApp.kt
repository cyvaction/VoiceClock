package com.cyvaction.voiceclock

import android.app.Application

class VoiceClockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ResidentService.start(this)
    }
}
