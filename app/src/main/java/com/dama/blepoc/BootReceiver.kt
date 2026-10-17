package com.dama.blepoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dama.blepoc.sync.BleLocationSyncService

/**
 * Restarts the GPS sync foreground service after device boot, if sync was enabled.
 * The service itself resumes from persisted params on a null-intent start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("blepoc_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sync_enabled", false)) return
        val service = Intent(context, BleLocationSyncService::class.java)
        ContextCompat.startForegroundService(context, service)
    }
}
