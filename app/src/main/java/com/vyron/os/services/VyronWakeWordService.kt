package com.vyron.os.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.vyron.os.ui.VyronOverlayActivity

class VyronWakeWordService : Service() {

    companion object {
        private const val TAG = "VyronWakeWordService"
        private const val CHANNEL_ID = "VyronWakeChannel"
        private const val NOTIFICATION_ID = 2026
        
        const val ACTION_START_LISTENING = "com.vyron.os.action.START_LISTENING"
        const val ACTION_STOP_LISTENING  = "com.vyron.os.action.STOP_LISTENING"
    }

    private var porcupineDetector: PorcupineWakeWordDetector? = null
    private var isListeningActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initPorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING  -> stopListening()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initPorcupine() {
        porcupineDetector = PorcupineWakeWordDetector(
            context = this,
            onWakeWordDetected = {
                Log.d(TAG, "Offline Wake word detected!")
                stopListening()
                launchOverlay()
            },
            onApiFailure = { error ->
                Log.e(TAG, "Porcupine API failure: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this, "Wake Word Engine Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startListening() {
        if (isListeningActive) return
        porcupineDetector?.start()
        isListeningActive = true
        Log.d(TAG, "Continuous offline wake word listening started.")
    }

    private fun stopListening() {
        if (!isListeningActive) return
        porcupineDetector?.stop()
        isListeningActive = false
        Log.d(TAG, "Continuous offline wake word listening stopped.")
    }

    private fun launchOverlay() {
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
            Log.d(TAG, "Overlay launched successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch overlay: ${e.message}")
            launchViaNotification(intent)
        }
    }

    private fun launchViaNotification(intent: Intent) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT

            val pi = android.app.PendingIntent.getActivity(this, 1, intent, flags)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VYRON AI")
                .setContentText("Tap to open assistant")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Notification fallback failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "VYRON Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = null
                enableLights(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ VYRON AI Active")
            .setContentText("Listening offline for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setSilent(true)
            .build()
}
