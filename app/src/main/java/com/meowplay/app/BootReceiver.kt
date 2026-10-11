package com.meowplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            if (MeowPlayConfigManager.getAutoStartOnBoot(context)) {
                Log.i("BootReceiver", "Device booted. Auto-start enabled: Starting MeowPlay 🐱 Service...")
                MeowPlayService.start(context)
            } else {
                Log.i("BootReceiver", "Device booted. Auto-start disabled in MeowPlay settings.")
            }
        }
    }
}
