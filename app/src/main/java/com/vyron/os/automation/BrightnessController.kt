package com.vyron.os.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object BrightnessController {

    // Helper: Check and request write settings permission
    private fun checkPermission(context: Context): Boolean {
        return if (Settings.System.canWrite(context)) {
            true
        } else {
            Toast.makeText(context, "VYRON OS requires System Write settings permission.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            false
        }
    }

    // Set brightness to a specific percentage (e.g. 60%)
    fun setBrightness(context: Context, percentage: Int): Boolean {
        if (!checkPermission(context)) return false

        return try {
            val brightnessVal = (255 * (percentage / 100.0)).toInt()
            
            // Set mode to manual first
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessVal
            )
            
            Toast.makeText(context, "Screen brightness set to $percentage%", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Brightness Error: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // Max and Min Brightness shortcuts
    fun setMaxBrightness(context: Context): Boolean {
        return setBrightness(context, 100)
    }

    fun setMinBrightness(context: Context): Boolean {
        return setBrightness(context, 1)
    }

    // Enable Automatic Brightness adjustment
    fun enableAutoBrightness(context: Context): Boolean {
        if (!checkPermission(context)) return false
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            Toast.makeText(context, "Auto Brightness Enabled", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Auto Brightness Error: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // Increase / Decrease screen brightness smoothly
    fun adjustBrightness(context: Context, increase: Boolean): Boolean {
        if (!checkPermission(context)) return false
        return try {
            val currentVal = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val step = 25 // Roughly 10% steps
            val newVal = if (increase) {
                minOf(255, currentVal + step)
            } else {
                maxOf(0, currentVal - step)
            }
            
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                newVal
            )
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Brightness Adjust Error: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }


    // Get current screen brightness percentage
    fun getBrightnessStatus(context: Context): String {
        return try {
            val currentVal = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val percent = ((currentVal.toFloat() / 255.0f) * 100).toInt()
            "Current screen brightness is at $percent percent."
        } catch (e: Exception) {
            "Unable to read current screen brightness."
        }
    }
}
