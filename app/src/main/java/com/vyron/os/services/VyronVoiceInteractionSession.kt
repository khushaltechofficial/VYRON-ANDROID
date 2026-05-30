package com.vyron.os.services

import android.service.voice.VoiceInteractionSession
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.vyron.os.ui.VyronOverlayActivity

class VyronVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "VyronSession"
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow triggered via system assistant action.")

        val intent = Intent(context, VyronOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val accessibilityService =
            com.vyron.os.automation.VyronAccessibilityService.instance

        try {
            if (accessibilityService != null) {
                accessibilityService.startActivity(intent)
            } else {
                context.startActivity(intent)
            }
            Log.d(TAG, "Launched overlay successfully from session.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch overlay from session: ${e.message}")
        }
        
        // Dismiss the session window
        hide()
    }
}
