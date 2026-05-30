package com.vyron.os.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.vyron.os.services.ReminderReceiver
import java.util.Calendar
import java.util.Locale

object ReminderManager {

    private const val TAG = "VyronReminder"

    private fun getNextReminderId(context: Context): Int {
        val prefs = context.getSharedPreferences("VyronReminderPrefs", Context.MODE_PRIVATE)
        val currentId = prefs.getInt("last_reminder_id", 0)
        val nextId = (currentId + 1) % 50000 // Keep bounded and safe
        prefs.edit().putInt("last_reminder_id", nextId).apply()
        return nextId
    }

    // Set a natural language reminder
    fun setSmartReminder(context: Context, timeExpression: String, messageText: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = Calendar.getInstance()

        // Natural Language Parser
        val cleanTime = timeExpression.lowercase(Locale.ROOT).trim()
        var delayMillis: Long = -1

        when {
            cleanTime.contains("minute") || cleanTime.contains("min") -> {
                val num = cleanTime.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 5
                delayMillis = num * 60 * 1000L
                calendar.add(Calendar.MINUTE, num)
            }
            cleanTime.contains("hour") || cleanTime.contains("hr") -> {
                val num = cleanTime.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                delayMillis = num * 60 * 60 * 1000L
                calendar.add(Calendar.HOUR, num)
            }
            cleanTime.contains("sec") -> {
                val num = cleanTime.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 10
                delayMillis = num * 1000L
                calendar.add(Calendar.SECOND, num)
            }
            cleanTime.contains("tomorrow") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                if (cleanTime.contains("am")) {
                    calendar.set(Calendar.HOUR_OF_DAY, 8)
                    calendar.set(Calendar.MINUTE, 0)
                } else if (cleanTime.contains("pm")) {
                    calendar.set(Calendar.HOUR_OF_DAY, 20)
                    calendar.set(Calendar.MINUTE, 0)
                }
            }
        }

        if (delayMillis == -1L && !cleanTime.contains("tomorrow")) {
            // Default: 5 minutes fallback
            delayMillis = 5 * 60 * 1000L
            calendar.add(Calendar.MINUTE, 5)
        }

        val triggerTime = calendar.timeInMillis

        // Setup reminder intent for receiver
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, "VYRON OS REMINDER")
            putExtra(ReminderReceiver.EXTRA_BODY, messageText)
        }

        // Configure pending intent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val reminderId = getNextReminderId(context)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId,
            intent,
            flags
        )

        // Schedule exact alarm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Reminder scheduled for $triggerTime: $messageText")
        } catch (e: SecurityException) {
            // Android 12+ requires special permission to set exact alarms
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }
}
