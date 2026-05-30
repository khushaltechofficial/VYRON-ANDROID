package com.vyron.os.automation

import android.content.Context
import android.hardware.camera2.CameraManager
import android.widget.Toast

object FlashlightController {

    private var isFlashOn = false

    // Turn flashlight ON instantly safely checking bounds
    fun turnOn(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val idList = cameraManager.cameraIdList
            if (idList.isNotEmpty()) {
                val cameraId = idList[0] // Main rear camera
                cameraManager.setTorchMode(cameraId, true)
                isFlashOn = true
            } else {
                Toast.makeText(context, "No camera flash hardware detected.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Flashlight Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Turn flashlight OFF instantly safely checking bounds
    fun turnOff(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val idList = cameraManager.cameraIdList
            if (idList.isNotEmpty()) {
                val cameraId = idList[0]
                cameraManager.setTorchMode(cameraId, false)
                isFlashOn = false
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Flashlight Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle flashlight state instantly
    fun toggle(context: Context) {
        if (isFlashOn) {
            turnOff(context)
        } else {
            turnOn(context)
        }
    }
}
