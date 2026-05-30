package com.vyron.os.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.vyron.os.automation.GoogleTTS
import com.vyron.os.getSecureSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class VyronTTS(
    private val context: Context,
    private val onSpeechStatusChanged: (status: String) -> Unit,
    private val onSpeechFinished: (utteranceId: String) -> Unit,
    private val onSpeechError: (utteranceId: String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingSpeechQueue = mutableListOf<PendingSpeech>()
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private data class PendingSpeech(
        val text: String,
        val utteranceId: String,
        val closeAfter: Boolean
    )

    companion object {
        private const val TAG = "VyronTTS"
        private const val SHORT_REPLY_THRESHOLD = 40
    }

    init {
        tts = TextToSpeech(context, this, "com.google.android.tts")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val currentLocale = Locale.getDefault()
            val result = tts?.setLanguage(currentLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Default locale not supported by TTS. Falling back to US English.")
                tts?.language = Locale.US
            }

            // Set warm companion voice characteristics
            tts?.setPitch(1.05f)
            tts?.setSpeechRate(0.92f)

            // Select premium friendly voice profile if available
            try {
                val voices = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    val targetLang = tts?.language?.language ?: currentLocale.language
                    val bestVoice = voices.find { voice ->
                        voice.locale.language == targetLang &&
                        voice.name.contains("-x-", ignoreCase = true) &&
                        (voice.name.contains("female", ignoreCase = true) || voice.name.contains("fem", ignoreCase = true))
                    } ?: voices.find { voice ->
                        voice.locale.language == targetLang &&
                        voice.name.contains("-x-", ignoreCase = true)
                    } ?: voices.find { voice ->
                        voice.locale.language == targetLang &&
                        (voice.name.contains("female", ignoreCase = true) || voice.name.contains("network", ignoreCase = true))
                    } ?: voices.find { it.locale.language == targetLang }

                    if (bestVoice != null) {
                        tts?.voice = bestVoice
                        Log.d(TAG, "Selected friendly local premium voice: ${bestVoice.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to select specific friendly voice profile, using default.", e)
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "Local TTS Started: $utteranceId")
                    onSpeechStatusChanged("SPEAKING")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "Local TTS Completed: $utteranceId")
                    onSpeechFinished(utteranceId ?: "")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Local TTS Error: $utteranceId")
                    onSpeechError(utteranceId ?: "")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "Local TTS Error ($errorCode): $utteranceId")
                    onSpeechError(utteranceId ?: "")
                }
            })

            isTtsInitialized = true
            flushPendingSpeechQueue()
        } else {
            Log.e(TAG, "Local TextToSpeech initialization failed with status: $status")
        }
    }

    private fun flushPendingSpeechQueue() {
        synchronized(pendingSpeechQueue) {
            for (pending in pendingSpeechQueue) {
                speak(pending.text, pending.utteranceId, pending.closeAfter)
            }
            pendingSpeechQueue.clear()
        }
    }

    fun speak(text: String, utteranceId: String = "VyronSpeechID", closeAfter: Boolean = true) {
        val securePref = getSecureSharedPreferences(context)
        val ttsApiKey = securePref.getString("google_tts_api_key", "") ?: ""

        // Routing Rules:
        // 1. If length < 40 -> Short reply -> Use zero-cost local Android TTS
        // 2. If length >= 40 and API Key is configured -> Premium Google Cloud TTS
        // 3. Fallback -> Local Android Neural TTS
        if (text.length >= SHORT_REPLY_THRESHOLD && ttsApiKey.isNotEmpty()) {
            mainScope.launch {
                onSpeechStatusChanged("SPEAKING")
                val success = GoogleTTS.speak(context, text, ttsApiKey)
                if (success) {
                    onSpeechFinished(utteranceId)
                } else {
                    Log.e(TAG, "Google Cloud TTS failed, falling back to local TTS engine.")
                    speakLocal(text, utteranceId, closeAfter)
                }
            }
        } else {
            speakLocal(text, utteranceId, closeAfter)
        }
    }

    private fun speakLocal(text: String, utteranceId: String, closeAfter: Boolean) {
        if (!isTtsInitialized) {
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.add(PendingSpeech(text, utteranceId, closeAfter))
            }
            Log.d(TAG, "Local TTS not ready, queued speech.")
            return
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        isTtsInitialized = false
    }
}
