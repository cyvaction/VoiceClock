package com.cyvaction.voiceclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (intent.action in BOOT_ACTIONS) {
            ResidentService.start(context)
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsStore.get(context)
                AnnouncementScheduler.reschedule(context)
                if (settings.batteryEnabled) BatteryWorker.schedule(context)
                else BatteryWorker.cancel(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val OEM_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val HUAWEI_QUICKBOOT_POWERON = "com.huawei.intent.action.QUICKBOOT_POWERON"
        const val OPPO_QUICKBOOT_POWERON = "com.android.server.action.QUICKBOOT_POWERON"

        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            OEM_QUICKBOOT_POWERON,
            HUAWEI_QUICKBOOT_POWERON,
            OPPO_QUICKBOOT_POWERON,
        )

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            OEM_QUICKBOOT_POWERON,
            HUAWEI_QUICKBOOT_POWERON,
            OPPO_QUICKBOOT_POWERON,
        )
    }
}
