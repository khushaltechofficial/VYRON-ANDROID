package com.vyron.os.automation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GoogleTTS {
    private const val TAG = "GoogleTTS"

    suspend fun speak(context: Context, text: String, apiKey: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://texttospeech.googleapis.com/v1beta1/text:synthesize?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.doOutput = true

            val jsonInput = JSONObject().apply {
                put("input", JSONObject().apply { put("text", text) })
                put("voice", JSONObject().apply {
                    put("languageCode", "en-US")
                    put("name", "en-US-Chirp3-HD-Fenrir")
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "LINEAR16")
                    put("sampleRateHertz", 24000)
                })
            }

            conn.outputStream.use { os ->
                val input = jsonInput.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val audioContentBase64 = jsonResponse.getString("audioContent")
                val audioBytes = Base64.decode(audioContentBase64, Base64.DEFAULT)
                
                playAudioBytes(audioBytes)
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Google TTS failed: Code $responseCode, error: $errText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in GoogleTTS speak", e)
        }
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        val sampleRate = 24000
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, audioBytes.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(audioBytes, 0, audioBytes.size)
        audioTrack.play()

        // Wait for audio to finish playing
        val playState = audioTrack.playState
        val durationMs = (audioBytes.size.toDouble() / (sampleRate * 2) * 1000).toLong()
        try {
            Thread.sleep(durationMs + 100)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Audio play interrupted", e)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {}
        }
    }
}
