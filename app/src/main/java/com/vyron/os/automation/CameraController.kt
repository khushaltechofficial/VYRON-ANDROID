package com.vyron.os.automation

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale

object CameraController {

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private const val TAG = "VyronCamera"

    // Launch native system camera application
    fun openCamera(context: Context) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch Camera app.", Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle camera lens state (front/back)
    fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    // Capture a high-res photo hands-free using Jetpack CameraX
    fun takePhoto(context: Context, onComplete: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                
                // Configure CameraX ImageCapture
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                // Bind CameraX to application context lifecycle
                cameraProvider.unbindAll()
                
                // Note: To take a background photo without an active visible activity,
                // we bind it temporarily. Inside the app, the MainActivity can act as the binding source.
                if (context is LifecycleOwner) {
                    cameraProvider.bindToLifecycle(
                        context,
                        cameraSelector,
                        imageCapture
                    )
                    
                    executeCapture(context, imageCapture, onComplete)
                } else {
                    // Fallback to launching system camera if overlay is in front
                    openCamera(context)
                    onComplete()
                }

            } catch (e: Exception) {
                Log.e(TAG, "CameraX initialization failed", e)
                Toast.makeText(context, "CameraX error: ${e.message}", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun executeCapture(context: Context, imageCapture: ImageCapture, onComplete: () -> Unit) {
        // Prepare file name and content values
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VYRON_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VyronOS")
            }
        }

        // Configure output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Capture photo
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Play synthesized Shutter Sound safely releasing ToneGenerator
                    try {
                        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                toneGenerator.release()
                            } catch (e: Exception) {}
                        }, 500)
                    } catch (e: Exception) {}

                    Toast.makeText(context, "Photo taken & saved successfully!", Toast.LENGTH_LONG).show()
                    onComplete()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(context, "Capture Failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            }
        )
    }
}
