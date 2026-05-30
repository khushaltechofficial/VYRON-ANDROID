package com.vyron.os.services

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineManagerErrorCallback
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import android.content.Context
import android.util.Log

class PorcupineWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onApiFailure: (String) -> Unit
) {
    private var porcupineManager: PorcupineManager? = null
    private var isListening = false

    companion object {
        private const val TAG = "PorcupineDetector"
    }

    fun start() {
        if (isListening) {
            Log.d(TAG, "Porcupine is already running.")
            return
        }

        val securePref = com.vyron.os.getSecureSharedPreferences(context)
        val accessKey = securePref.getString("picovoice_access_key", "") ?: ""

        if (accessKey.isEmpty()) {
            onApiFailure("Picovoice Access Key is empty. Please enter your key in Settings.")
            return
        }

        try {
            val callback = PorcupineManagerCallback { keywordIndex ->
                Log.d(TAG, "Wake word detected at index: $keywordIndex")
                onWakeWordDetected()
            }

            val errorCallback = PorcupineManagerErrorCallback { error ->
                Log.e(TAG, "Porcupine error: ${error.message}")
                onApiFailure(error.localizedMessage ?: "Porcupine runtime error")
            }

            val builder = PorcupineManager.Builder().setAccessKey(accessKey)

            // Look for custom PPN files in assets
            val assetsList = context.assets.list("") ?: emptyArray()
            val customPpn = when {
                assetsList.contains("hey_vyron_android.ppn") -> "hey_vyron_android.ppn"
                assetsList.contains("Panda_en_android_v3_0_0.ppn") -> "Panda_en_android_v3_0_0.ppn"
                else -> null
            }

            if (customPpn != null) {
                Log.d(TAG, "Using custom PPN file from assets: $customPpn")
                builder.setKeywordPaths(arrayOf(customPpn))
            } else {
                Log.d(TAG, "Custom PPN file not found in assets, falling back to built-in JARVIS.")
                builder.setKeywords(arrayOf(BuiltInKeyword.JARVIS))
            }

            builder.setSensitivity(0.5f)
            builder.setErrorCallback(errorCallback)

            porcupineManager = builder.build(context, callback)
            porcupineManager?.start()
            isListening = true
            Log.d(TAG, "Porcupine detector started successfully offline.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}")
            onApiFailure(e.localizedMessage ?: "Porcupine initialization error")
        }
    }

    fun stop() {
        if (!isListening) return
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine: ${e.message}")
        } finally {
            porcupineManager = null
            isListening = false
            Log.d(TAG, "Porcupine detector stopped successfully.")
        }
    }
}
