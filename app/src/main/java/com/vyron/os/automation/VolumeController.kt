package com.vyron.os.automation

import android.content.Context
import android.media.AudioManager
import android.widget.Toast

object VolumeController {

    private var cachedVolume = -1

    // Adjust volume smoothly (raise/lower)
    fun adjustVolume(context: Context, raise: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // Set Volume to 100% (Max)
    fun setMaxVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, AudioManager.FLAG_SHOW_UI)
        Toast.makeText(context, "Volume set to MAX (100%)", Toast.LENGTH_SHORT).show()
    }

    // Set exact volume level (e.g. 70%)
    fun setVolumeLevel(context: Context, percentage: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val calculatedVol = (maxVol * (percentage / 100.0)).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, calculatedVol, AudioManager.FLAG_SHOW_UI)
        Toast.makeText(context, "Volume set to $percentage%", Toast.LENGTH_SHORT).show()
    }

    // Quick Mute / Unmute media stream
    fun mute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol > 0) {
            cachedVolume = currentVol
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            Toast.makeText(context, "Media muted", Toast.LENGTH_SHORT).show()
        }
    }

    fun unmute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (cachedVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cachedVolume, AudioManager.FLAG_SHOW_UI)
            Toast.makeText(context, "Volume restored", Toast.LENGTH_SHORT).show()
            cachedVolume = -1
        } else {
            val defaultRestore = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, defaultRestore, AudioManager.FLAG_SHOW_UI)
        }
    }

    // Switch sound profiles: Silent, Vibrate, Normal
    fun setSoundProfile(context: Context, mode: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Changing ringer mode can require ACCESS_NOTIFICATION_POLICY on some Android systems
        try {
            when (mode.uppercase()) {
                "SILENT" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    Toast.makeText(context, "Profile: Silent Mode", Toast.LENGTH_SHORT).show()
                }
                "VIBRATE" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    Toast.makeText(context, "Profile: Vibrate Mode", Toast.LENGTH_SHORT).show()
                }
                "NORMAL" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    Toast.makeText(context, "Profile: Normal Mode", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Profile Error: Allow Notification access first.", Toast.LENGTH_LONG).show()
        }
    }

    // Fetch exact media volume levels
    fun getVolumeStatus(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percentage = ((current.toFloat() / max.toFloat()) * 100).toInt()
        return "Current exact media volume is $percentage percent."
    }
}
