package com.joey.powerwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Resume only monitoring that was enabled before reboot, after credential storage is available. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences(MonitoringService.PREFS, Context.MODE_PRIVATE)
        if (!shouldResumeAfterBoot(
                preferences.getBoolean("active", false),
                preferences.getBoolean("start_after_reboot", true))) return
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_BOOT))
        } catch (error: IllegalStateException) {
            reportFailure(context, "Android blocked restart after reboot: " + error.message, error)
        } catch (error: SecurityException) {
            reportFailure(context, "Permission denied restarting after reboot: " + error.message, error)
        }
    }

    private fun reportFailure(context: Context, message: String, error: Exception) {
        Log.e("PowerWatch", message, error)
        context.getSharedPreferences(MonitoringService.PREFS, Context.MODE_PRIVATE)
            .edit().putString("boot_start_error", message).apply()
    }
}

internal fun shouldResumeAfterBoot(wasMonitoring: Boolean, restartEnabled: Boolean): Boolean =
    wasMonitoring && restartEnabled
