package com.vyron.os.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vyron.os.MainActivity
import com.vyron.os.ui.VyronOverlayActivity
import java.util.Locale

class VyronWakeWordService : Service() {

    companion object {
        const val ACTION_START_LISTENING = "com.vyron.os.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.vyron.os.action.STOP_LISTENING"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening = false
    private var isListeningEnabled = true
    private var audioManager: AudioManager? = null
    private var originalMusicVolume = -1
    private var originalSystemVolume = -1
    private var originalNotificationVolume = -1
    private var isMuted = false
    private var toneGenerator: ToneGenerator? = null
    
    private var lastResumedTime = 0L
    private var consecutiveErrors = 0
    
    private val TAG = "VyronWakeWord"
    private val CHANNEL_ID = "VyronWakeChannel"
    private val NOTIFICATION_ID = 2026


    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator initialization failed", e)
        }
        createNotificationChannel()
        startForegroundService()
        initializeSpeechRecognizer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VYRON OS Wake Word Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VYRON OS Active")
            .setContentText("Listening for 'HEY VYRON' wake-word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)

            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                consecutiveErrors = 0
                Log.d(TAG, "Speech recognizer ready and listening...")
                // Cancel backup runnable and schedule fresh unmute after 1.5 seconds
                mHandler.removeCallbacks(unmuteRunnable)
                mHandler.postDelayed(unmuteRunnable, 1500)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(TAG, "Recognizer error: $error. Restarting...")
                mHandler.removeCallbacks(unmuteRunnable)
                unmuteBeep()
                restartListening(error)
            }

            override fun onResults(results: Bundle?) {
                mHandler.removeCallbacks(unmuteRunnable)
                unmuteBeep()
                
                // Safety guard: If results arrive too soon after resuming, ignore to prevent TTS echo looping
                val timeSinceResume = System.currentTimeMillis() - lastResumedTime
                if (timeSinceResume < 4000) {
                    Log.d(TAG, "Speech ignored: too soon after resume ($timeSinceResume ms)")
                    restartListening()
                    return
                }

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.size > 0) {
                    val transcript = matches[0].lowercase(Locale.ROOT)
                    Log.d(TAG, "Heard: $transcript")
                    
                    // Match English and Devanagari (Hindi) wake word transcriptions
                    val matchesHindi = transcript.contains("वायरन") || 
                                       transcript.contains("वायरोन") || 
                                       transcript.contains("वाइरॉन") || 
                                       transcript.contains("वायरॉन") || 
                                       transcript.contains("वाय रन") ||
                                       transcript.contains("वाय")
                    val matchesEnglish = transcript.contains("vyron") || 
                                         transcript.contains("viron") || 
                                         transcript.contains("byron") || 
                                         transcript.contains("iron") || 
                                         transcript.contains("waron") || 
                                         transcript.contains("environ")
                    
                    if (matchesEnglish || matchesHindi) {
                        triggerWakeWordChime()
                        // Synchronously stop continuous listening first to release microphone
                        stopListening()
                        launchOverlayAssistant()
                    } else {
                        restartListening()
                    }
                } else {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private val mHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val startListeningRunnable = Runnable {
        startListening()
    }
    
    private val unmuteRunnable = Runnable {
        unmuteBeep()
    }

    private fun muteBeep() {
        if (isMuted) return
        try {
            audioManager?.let { am ->
                // Check if current ringer mode is normal
                val currentRingerMode = am.ringerMode
                if (currentRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                    originalSystemVolume = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                    // Set stream volume to 1 instead of 0. This keeps the stream silent,
                    // but prevents the device from switching to Vibrate/Silent mode automatically!
                    am.setStreamVolume(AudioManager.STREAM_SYSTEM, 1, 0)
                    isMuted = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute streams", e)
        }
    }

    private fun unmuteBeep() {
        if (!isMuted) return
        try {
            audioManager?.let { am ->
                try {
                    val currentRingerMode = am.ringerMode
                    if (currentRingerMode == AudioManager.RINGER_MODE_NORMAL && originalSystemVolume != -1) {
                        am.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore system stream volume", e)
                }
                isMuted = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute streams", e)
        }
    }

    private fun startListening() {
        isListeningEnabled = true
        
        // Dynamic RECORD_AUDIO runtime permission check (Bug 6)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing. Suspending background wake service.")
            showPermissionWarningNotification()
            return
        }

        if (!isListening) {
            muteBeep()
            speechRecognizer?.startListening(recognizerIntent)
            isListening = true
            
            // Backup unmute fallback after 3 seconds in case onReadyForSpeech is delayed or fails
            mHandler.removeCallbacks(unmuteRunnable)
            mHandler.postDelayed(unmuteRunnable, 3000)
        }
    }

    private fun showPermissionWarningNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, flags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VYRON Voice Suspended")
            .setContentText("Microphone permission is required to listen for HEY VYRON.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 50, notification)
    }

    private fun stopListening() {
        mHandler.removeCallbacks(startListeningRunnable)
        mHandler.removeCallbacks(unmuteRunnable)
        mHandler.removeCallbacks(restartListeningRunnable)
        isListeningEnabled = false
        isListening = false
        speechRecognizer?.cancel()
        unmuteBeep()
    }

    private val restartListeningRunnable = Runnable {
        if (!isListeningEnabled) return@Runnable
        startListening()
    }

    private fun restartListening(errorCode: Int = -1) {
        if (!isListeningEnabled) return
        isListening = false
        speechRecognizer?.cancel()
        unmuteBeep()
        
        mHandler.removeCallbacks(restartListeningRunnable)
        
        // Error backoff: Increase backoff delay if error is persistent to protect CPU and battery
        if (errorCode != -1) {
            consecutiveErrors++
        }
        val backoffDelay = if (consecutiveErrors > 3) 8000L else 2000L
        mHandler.postDelayed(restartListeningRunnable, backoffDelay)
    }

    // Play cybernetic notification chime safely using single managed instance (Bug 9)
    private fun triggerWakeWordChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Chime generation failed", e)
        }
    }

    // Launch translucent bottom overlay securely bypassing background restrictions
    private fun launchOverlayAssistant() {
        val accessibilityService = com.vyron.os.automation.VyronAccessibilityService.instance
        
        val intent = Intent(this, VyronOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (accessibilityService != null) {
            accessibilityService.startActivity(intent)
            Log.d(TAG, "Overlay started successfully via Accessibility Service.")
        } else {
            // Fallback: Launch via High-Priority Notification Full Screen Intent
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, flags)
                
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("VYRON OS Summoned")
                    .setContentText("Voice assistant is active.")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pendingIntent, true) // Secure Full Screen Intent
                    .setAutoCancel(true)
                    .build()
                
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID + 10, notification)
                Log.d(TAG, "Overlay launched via High Priority Notification Intent.")
            } catch (e: Exception) {
                // Last fallback: Standard start
                startActivity(intent)
                Log.d(TAG, "Overlay launched via standard fallback.")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_LISTENING -> {
                    Log.d(TAG, "Resuming background voice listener with 3.5s cooldown...")
                    lastResumedTime = System.currentTimeMillis()
                    mHandler.removeCallbacks(startListeningRunnable)
                    mHandler.postDelayed(startListeningRunnable, 3500) // 3.5s feedback loop cooldown
                }
                ACTION_STOP_LISTENING -> {
                    Log.d(TAG, "Pausing background voice listener (mic delegated)...")
                    stopListening()
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacksAndMessages(null) // Prevent Handler leaks (Bug 17)
        speechRecognizer?.destroy()
        toneGenerator?.release() // Release ToneGenerator resource (Bug 9)
        unmuteBeep()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
