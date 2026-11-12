package com.nasser.batteryguardai

import android.app.Application
import com.nasser.batteryguardai.engine.Notifier
import com.nasser.batteryguardai.engine.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        Scheduler.schedule(this)
    }
}
