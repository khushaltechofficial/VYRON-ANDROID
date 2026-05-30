package com.vyron.os.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted successfully. Launching VYRON Voice wake daemon...")
            
            val serviceIntent = Intent(context, VyronWakeWordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "VYRON Voice wake daemon launched on boot successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch service on boot: ${e.message}")
            }
        }
    }
}
