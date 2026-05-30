package com.vyron.os.automation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object SystemInfoManager {

    private const val TAG = "VyronSystemInfo"

    // Time & Date: Return current time, day, or full calendar date
    fun getDateTimeDetails(): String {
        val calendar = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
        
        val timeStr = timeFormat.format(calendar.time)
        val dateStr = dateFormat.format(calendar.time)
        
        return "Right now, it is $timeStr on $dateStr."
    }

    // Battery Status: Retrieve battery level and charging state
    fun getBatteryStatus(context: Context): String {
        val batteryStatusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val state = if (isCharging) "currently charging" else "discharging"
        return "Your battery is at $batteryPct percent and is $state."
    }

    // Geolocation: Find exact current address
    @SuppressLint("MissingPermission")
    fun getCurrentLocationAddress(context: Context, callback: (String) -> Unit) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = LocationManager.NETWORK_PROVIDER // Network GPS is fast and works indoors
            
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                parseLocationToAddress(context, location.latitude, location.longitude, callback)
            } else {
                // Request a fresh single update if cache is empty
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { loc ->
                        if (loc != null) {
                            parseLocationToAddress(context, loc.latitude, loc.longitude, callback)
                        } else {
                            callback("Unable to acquire fresh GPS signal.")
                        }
                    }
                } else {
                    callback("GPS coordinates cache is empty. Please open Maps to refresh location.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error", e)
            callback("Location access denied. Please grant GPS permissions.")
        }
    }

    private fun parseLocationToAddress(context: Context, lat: Double, lon: Double, callback: (String) -> Unit) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (addresses != null && addresses.size > 0) {
                val addressObj = addresses[0]
                val fullAddress = addressObj.getAddressLine(0) ?: "Unknown street"
                callback(fullAddress)
            } else {
                callback("Coordinates are Latitude: $lat, Longitude: $lon.")
            }
        } catch (e: Exception) {
            callback("Coordinates acquired: Latitude: $lat, Longitude: $lon. (Address translation failed)")
        }
    }

    // Weather: Query Open-Meteo REST API using coordinates or city
    fun getWeatherReport(context: Context, city: String, callback: (String) -> Unit) {
        if (city.isNotEmpty()) {
            // Retrieve weather by specified city (Geocode first)
            try {
                val geocoder = Geocoder(context)
                val addresses = geocoder.getFromLocationName(city, 1)
                if (addresses != null && addresses.size > 0) {
                    val lat = addresses[0].latitude
                    val lon = addresses[0].longitude
                    fetchWeatherFromAPI(lat, lon, city, callback)
                } else {
                    callback("Sorry Boss, I couldn't locate the city '$city' to get its weather.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding failed for weather", e)
                callback("Geocoding failed. Unable to resolve coordinates for '$city' offline.")
            }
        } else {
            // Retrieve weather for current GPS coordinates
            getCurrentLocationAddress(context) { address ->
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (loc != null) {
                        fetchWeatherFromAPI(loc.latitude, loc.longitude, "your current location", callback)
                    } else {
                        callback("Sorry Boss, I couldn't acquire your current GPS coordinates for weather reporting.")
                    }
                } catch (e: Exception) {
                    callback("Location access error. Unable to retrieve coordinates for weather reporting.")
                }
            }
        }
    }

    private fun fetchWeatherFromAPI(lat: Double, lon: Double, label: String, callback: (String) -> Unit) {
        // Run network operations in a background thread to prevent Main UI Thread freeze
        Thread {
            try {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Parse weather data
                    val json = JSONObject(response.toString())
                    val currentWeather = json.getJSONObject("current_weather")
                    val temp = currentWeather.getDouble("temperature")
                    val windSpeed = currentWeather.getDouble("windspeed")
                    val isDay = currentWeather.getInt("is_day") == 1
                    
                    val timeOfDay = if (isDay) "daylight" else "nighttime"

                    val report = "The weather for $label shows a temperature of $temp degrees Celsius, with wind speeds around $windSpeed kilometers per hour during $timeOfDay."
                    
                    callback(report)
                } else {
                    callback("Weather server returned an error (code ${conn.responseCode}). Please try again later.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Weather fetch failed", e)
                callback("Sorry Boss, I couldn't fetch live weather updates for $label right now due to a network connection issue.")
            }
        }.start()
    }
}
