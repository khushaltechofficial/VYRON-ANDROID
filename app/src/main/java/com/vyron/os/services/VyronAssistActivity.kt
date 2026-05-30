package com.vyron.os.services

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.vyron.os.ui.VyronOverlayActivity

class VyronAssistActivity : Activity() {

    companion object {
        private const val TAG = "VyronAssistActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VyronAssistActivity launched via system assist shortcut.")

        val intent = Intent(this, VyronOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val accessibilityService =
            com.vyron.os.automation.VyronAccessibilityService.instance

        try {
            if (accessibilityService != null) {
                accessibilityService.startActivity(intent)
            } else {
                startActivity(intent)
            }
            Log.d(TAG, "Vyron overlay launched from assist activity.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch overlay from assist activity: ${e.message}")
        }

        finish()
    }
}
