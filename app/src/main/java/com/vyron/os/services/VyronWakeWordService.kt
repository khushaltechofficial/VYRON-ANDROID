package com.vyron.os.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vyron.os.ui.VyronOverlayActivity
import java.util.Locale

class VyronWakeWordService : Service() {

    companion object {
        const val ACTION_START_LISTENING = "com.vyron.os.action.START_LISTENING"
        const val ACTION_STOP_LISTENING  = "com.vyron.os.action.STOP_LISTENING"
        private const val TAG = "VyronWakeWord"
        private const val CHANNEL_ID = "VyronWakeChannel"
        private const val NOTIFICATION_ID = 2026
        
        // Restart cooldown — beep loop rokne ke liye
        private const val RESTART_DELAY_MS = 1500L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListeningEnabled = true
    private var isRecognizerActive = false
    private var audioManager: AudioManager? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ─── Runnables ────────────────────────────────────────────
    private val restartRunnable = Runnable {
        if (isListeningEnabled) {
            doStartListening()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initRecognizer()
        doStartListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> scheduleRestart(RESTART_DELAY_MS)
            ACTION_STOP_LISTENING  -> pauseListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        restoreAllVolumes()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Core: mute ALL beep streams ─────────────────────────
    private val savedVolumes = mutableMapOf<Int, Int>()

    private val BEEP_STREAMS = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_DTMF,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING
    )

    private fun muteAllBeepStreams() {
        BEEP_STREAMS.forEach { stream ->
            try {
                if (!savedVolumes.containsKey(stream)) {
                    savedVolumes[stream] = audioManager?.getStreamVolume(stream) ?: 0
                }
                audioManager?.setStreamVolume(stream, 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not mute stream $stream: ${e.message}")
            }
        }
    }

    private fun restoreAllVolumes() {
        savedVolumes.forEach { (stream, vol) ->
            try {
                audioManager?.setStreamVolume(stream, vol, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore stream $stream: ${e.message}")
            }
        }
        savedVolumes.clear()
    }

    // ─── Recognizer Setup ─────────────────────────────────────
    private fun initRecognizer() {
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(wakeWordListener)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Silence thresholds — faster restart, less delay
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isRecognizerActive = false
    }

    // ─── Listen / Pause ──────────────────────────────────────
    private fun doStartListening() {
        if (!isListeningEnabled || isRecognizerActive) return

        muteAllBeepStreams()

        try {
            speechRecognizer?.startListening(recognizerIntent)
            isRecognizerActive = true
            Log.d(TAG, "Listening for wake word...")

            // Safety unmute after 4s — beep timing se zyaada
            mainHandler.postDelayed({ restoreAllVolumes() }, 4000L)

        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            restoreAllVolumes()
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    private fun pauseListening() {
        isListeningEnabled = false
        mainHandler.removeCallbacks(restartRunnable)
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isRecognizerActive = false
        restoreAllVolumes()
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    // ─── Wake Word Listener ───────────────────────────────────
    private val wakeWordListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            // Mic ready — beep already muted, schedule unmute
            mainHandler.postDelayed({ restoreAllVolumes() }, 300L)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isRecognizerActive = false
        }

        override fun onError(error: Int) {
            isRecognizerActive = false
            restoreAllVolumes()

            when (error) {
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Recognizer busy/audio problem — wait longer
                    Log.d(TAG, "Recognizer error $error — waiting 2s before restart")
                    destroyRecognizer()
                    mainHandler.postDelayed({
                        initRecognizer()
                        scheduleRestart(500L)
                    }, 2000L)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "Mic permission missing — stopping service")
                    stopSelf()
                }
                else -> {
                    // Normal timeout / no match — quick restart
                    scheduleRestart(RESTART_DELAY_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isRecognizerActive = false
            restoreAllVolumes()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.getOrNull(0)?.lowercase(Locale.ROOT) ?: ""

            Log.d(TAG, "Heard: $transcript")

            // Wake word check — "vyron" or "hey vyron" or "vyron" variants including Devanagari
            val wakeWords = listOf(
                "vyron", "hey vyron", "hi vyron", "hello vyron", "ok vyron", "viron", "wairon",
                "byron", "iron", "waron", "environ",
                "वायरन", "वायरोन", "वाइरॉन", "वायरॉन", "वाय रन", "वाय"
            )

            if (wakeWords.any { transcript.contains(it) }) {
                Log.d(TAG, "WAKE WORD DETECTED!")
                pauseListening()
                launchOverlay()
            } else {
                scheduleRestart(RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── Launch Overlay ───────────────────────────────────────
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
            Log.d(TAG, "Overlay launched")
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch overlay: ${e.message}")
            // Notification fallback
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

    // ─── Notification ─────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "VYRON Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }  // No sound on channel
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ VYRON AI Active")
            .setContentText("Say \"Hey VYRON\" anytime...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setSilent(true)
            .build()
}
