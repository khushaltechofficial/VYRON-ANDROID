package com.vyron.os.automation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object NetworkController {
    private const val TAG = "NetworkController"

    @SuppressLint("WifiManagerPotentialLeak")
    fun toggleWifi(context: Context, enable: Boolean): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                if (enable) "Turning on Wi-Fi..." else "Turning off Wi-Fi..."
            } else {
                // Android 10+ restricts programmatic WiFi toggle. Launch Panel or Settings as fallback.
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening connectivity panel to toggle Wi-Fi, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi", e)
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Wi-Fi settings, Boss."
            } catch (ex: Exception) {
                "Failed to access Wi-Fi controls."
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun toggleBluetooth(context: Context, enable: Boolean): String {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null) {
                return "Bluetooth is not supported on this device."
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                if (enable) {
                    bluetoothAdapter.enable()
                    "Enabling Bluetooth, Boss."
                } else {
                    bluetoothAdapter.disable()
                    "Disabling Bluetooth, Boss."
                }
            } else {
                // Android 12+ requires BLUETOOTH_CONNECT permission which is dynamic
                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    if (enable) {
                        bluetoothAdapter.enable()
                        "Enabling Bluetooth, Boss."
                    } else {
                        bluetoothAdapter.disable()
                        "Disabling Bluetooth, Boss."
                    }
                } else {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Please grant Bluetooth permission or toggle it in the settings page opened."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth", e)
            try {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Bluetooth settings, Boss."
            } catch (ex: Exception) {
                "Failed to access Bluetooth controls."
            }
        }
    }
}
