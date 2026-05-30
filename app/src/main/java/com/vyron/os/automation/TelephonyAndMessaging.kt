package com.vyron.os.automation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale


object TelephonyAndMessaging {

    // 1. Cellular Direct Call
    private fun isNumericOrPhoneNumber(str: String): Boolean {
        val clean = str.replace(Regex("[\\s\\-+()]"), "")
        return clean.isNotEmpty() && clean.all { it.isDigit() }
    }

    // 1. Cellular Direct Call
    @SuppressLint("MissingPermission")
    fun placePhoneCall(context: Context, contactName: String) {
        val resolved = resolveContactNumber(context, contactName)
        if (resolved == null && !isNumericOrPhoneNumber(contactName)) {
            Toast.makeText(context, "Contact '$contactName' not found.", Toast.LENGTH_LONG).show()
            return
        }
        val number = resolved ?: contactName
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            // Fallback to dialer if ACTION_CALL permission is missing
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // 2. Background SMS Sender
    fun sendSMS(context: Context, contactName: String, message: String) {
        val resolved = resolveContactNumber(context, contactName)
        if (resolved == null && !isNumericOrPhoneNumber(contactName)) {
            Toast.makeText(context, "Contact '$contactName' not found.", Toast.LENGTH_LONG).show()
            return
        }
        val number = resolved ?: contactName
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            Toast.makeText(context, "SMS sent to $contactName: $message", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback to system SMS composer if permission is missing
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // 3. WhatsApp autonomous messaging trigger
    fun sendWhatsAppMessage(context: Context, contactName: String, message: String) {
        val resolved = resolveContactNumber(context, contactName)
        if (resolved == null && !isNumericOrPhoneNumber(contactName)) {
            Toast.makeText(context, "Contact '$contactName' not found.", Toast.LENGTH_LONG).show()
            return
        }
        val rawNumber = resolved ?: contactName
        val cleanNumber = rawNumber.replace(Regex("[^0-9]"), "")
        if (cleanNumber.isEmpty()) {
            Toast.makeText(context, "Failed to parse a valid phone number for $contactName.", Toast.LENGTH_SHORT).show()
            return
        }
        val countryAdded = if (cleanNumber.length == 10) "91$cleanNumber" else cleanNumber // Default: India code

        try {
            val packageManager = context.packageManager
            val encodedMsg = URLEncoder.encode(message, "UTF-8")
            val url = "https://api.whatsapp.com/send?phone=$countryAdded&text=$encodedMsg"
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // Trigger background Accessibility Service automation after chat opens
            VyronAccessibilityService.instance?.let { service ->
                val automationIntent = Intent(service, VyronAccessibilityService::class.java).apply {
                    action = VyronAccessibilityService.ACTION_WHATSAPP_SEND
                    putExtra(VyronAccessibilityService.EXTRA_CONTACT, contactName)
                    putExtra(VyronAccessibilityService.EXTRA_MESSAGE, message)
                }
                android.os.Handler(context.mainLooper).postDelayed({
                    service.startService(automationIntent)
                }, 1000) // Delay to let WhatsApp open the chat window
            }

        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
        }
    }

    // 4. WhatsApp Audio/Video Call triggers
    fun initiateWhatsAppVoiceCall(context: Context, contactName: String) {
        val number = resolveContactNumber(context, contactName) ?: contactName
        triggerWhatsAppCall(context, number, false)
    }

    fun initiateWhatsAppVideoCall(context: Context, contactName: String) {
        val number = resolveContactNumber(context, contactName) ?: contactName
        triggerWhatsAppCall(context, number, true)
    }

    private fun triggerWhatsAppCall(context: Context, number: String, isVideo: Boolean) {
        try {
            val cleanNumber = number.replace(Regex("[^0-9]"), "")
            val formatted = if (cleanNumber.length == 10) "91$cleanNumber" else cleanNumber
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("whatsapp://send?phone=$formatted")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Initiating WhatsApp call to $formatted...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp Calls not supported.", Toast.LENGTH_SHORT).show()
        }
    }

    // 5. Smart Contact Lookup (Resolves names to numbers, handling conflicts)
    private fun resolveContactNumber(context: Context, name: String): String? {
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        
        var resolvedNumber: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                resolvedNumber = it.getString(numIdx)
                
                // Smart select logic: If multiple entries, Log names or pick first
                val count = it.count
                if (count > 1) {
                    Log.d("VyronContacts", "Multiple contact matches found for $name. Selecting first match: $resolvedNumber")
                }
            }
        }
        return resolvedNumber
    }

    // 6. Google Maps: Start Turn-by-Turn Navigation
    fun startNavigation(context: Context, destination: String) {
        val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            // Web maps fallback
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    // 7. Google Maps: View target Location on Map
    fun viewOnMap(context: Context, place: String) {
        val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(mapIntent)
    }

    // 8. Google Maps: Search Maps general queries
    fun searchMap(context: Context, query: String) {
        viewOnMap(context, query)
    }

    // 9. Google Maps: Find Nearby Places (ATMs, restaurants, petrol pumps)
    fun findNearby(context: Context, placeType: String) {
        val query = "nearby $placeType"
        viewOnMap(context, query)
    }

    // 10. Spotify: Search and Play music tracks
    fun playSpotifySong(context: Context, songQuery: String) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(MediaStore.EXTRA_MEDIA_ARTIST, "")
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, songQuery)
                putExtra("query", songQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

        } catch (e: Exception) {
            // Launch Spotify package fallback
            launchApp(context, "Spotify")
        }
    }

    // 11. Spotify: Resume last playing media
    fun resumeSpotifyPlayback(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        audioManager.dispatchMediaKeyEvent(event)
    }

    // 12. App Launcher: Launch any app instantly by name
    fun launchApp(context: Context, appName: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        
        var targetPackage: String? = null
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
            if (label.contains(appName.lowercase(Locale.ROOT))) {
                targetPackage = app.packageName
                break
            }
        }

        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            } else {
                Toast.makeText(context, "App '$appName' has no launcher interface.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "App '$appName' is not installed.", Toast.LENGTH_SHORT).show()
        }
    }
}
