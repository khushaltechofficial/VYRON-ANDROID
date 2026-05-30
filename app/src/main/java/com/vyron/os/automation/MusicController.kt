package com.vyron.os.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.util.Log
import android.widget.Toast

object MusicController {
    private const val TAG = "MusicController"

    fun playSpotify(context: Context, query: String) {
        Log.d(TAG, "Attempting to play query on Spotify: '$query'")
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Searching & playing '$query' on Spotify", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Media Search intent, falling back to Spotify app launch", e)
            launchSpotifyFallback(context)
        }
    }

    fun resume(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            audioManager.dispatchMediaKeyEvent(eventDown)
            
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(eventUp)
            
            Log.d(TAG, "Dispatched media keycode: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch media key event for keycode $keyCode", e)
        }
    }

    private fun launchSpotifyFallback(context: Context) {
        val pm = context.packageManager
        try {
            val intent = pm.getLaunchIntentForPackage("com.spotify.music")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "Opening Spotify app...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Spotify is not installed.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify app launch failed", e)
            Toast.makeText(context, "Failed to open Spotify.", Toast.LENGTH_SHORT).show()
        }
    }
}
