package com.vyron.os.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.client.generativeai.GenerativeModel
import com.vyron.os.automation.FlashlightController
import com.vyron.os.automation.VolumeController
import com.vyron.os.automation.BrightnessController
import com.vyron.os.automation.CameraController
import com.vyron.os.automation.SystemInfoManager
import com.vyron.os.automation.ReminderManager
import com.vyron.os.automation.TelephonyAndMessaging
import com.vyron.os.automation.VyronAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

class VyronOverlayActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    
    private val BEEP_STREAMS = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_DTMF,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING
    )
    private val savedOverlayVolumes = mutableMapOf<Int, Int>()
    private var isMuted = false

    private var isTtsInitialized = false
    private val pendingSpeechQueue = mutableListOf<String>()

    private var userSpeech by mutableStateOf("Listening...")
    private var vyronReply by mutableStateOf("")
    private var currentStatus by mutableStateOf("LISTENING") // LISTENING, THINKING, SPEAKING, IDLE
    private var isSheetVisible by mutableStateOf(false)

    private val TAG = "VyronOverlayActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup System UI flags for immersive layout
        window.decorView.setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this, "com.google.android.tts")
        
        // Stop background wake listener so this overlay has exclusive mic access
        stopBackgroundWakeService()
        
        val securePref = com.vyron.os.getSecureSharedPreferences(this)
        val apiKey = securePref.getString("gemini_api_key", "") ?: ""
        
        if (apiKey.isEmpty()) {
            userSpeech = "API Key Missing!"
            vyronReply = "Please open the Vyron OS settings in the main app to enter your personal API Key."
            currentStatus = "IDLE"
        } else {
            val manualCommand = intent.getStringExtra("extra_command_text")
            if (!manualCommand.isNullOrEmpty()) {
                userSpeech = manualCommand
                currentStatus = "THINKING"
                android.os.Handler(mainLooper).postDelayed({
                    processUserCommand(manualCommand)
                }, 500)
            } else {
                // summond via voice wake word! Speak warm greeting first,
                // and start listening only after the greeting finishes speaking!
                val greeting = if (Locale.getDefault().language == "hi") "जी बॉस! बोलिए।" else "Ji Boss! Boliye."
                userSpeech = "Heard: Vyron"
                currentStatus = "SPEAKING"
                android.os.Handler(mainLooper).postDelayed({
                    speakReply(greeting, "VyronGreetingID")
                }, 300)
            }
        }

        setContent {
            LaunchedEffect(Unit) {
                isSheetVisible = true
            }
            OverlayHUD()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val manualCommand = intent?.getStringExtra("extra_command_text")
        if (!manualCommand.isNullOrEmpty()) {
            userSpeech = manualCommand
            currentStatus = "THINKING"
            processUserCommand(manualCommand)
        }
    }




    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val currentLocale = Locale.getDefault()
            val result = tts?.setLanguage(currentLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Default locale not supported by TTS. Falling back to US English.")
                tts?.language = Locale.US
            }

            // Set warmer and friendlier tone qualities
            // Warm, whisper-like friendly tone and rate
            tts?.setPitch(1.05f)     // Warmer companion frequency
            tts?.setSpeechRate(0.92f) // Relaxed conversational cadence

            // Try selecting a high-quality Google neural/wavenet voice if available
            try {
                val voices = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    val targetLang = tts?.language?.language ?: currentLocale.language
                    // Prioritize premium Wavernet/Neural voices with "-x-" in their names, and look for female variants
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
                        Log.d(TAG, "Selected friendly premium voice: ${bestVoice.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to select specific friendly voice profile, using default.", e)
            }

            // Set UtteranceProgressListener to handle voice state transitions
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS Started: $utteranceId")
                    currentStatus = "SPEAKING"
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS Completed: $utteranceId")
                    when (utteranceId) {
                        "VyronGreetingID" -> {
                            android.os.Handler(mainLooper).post {
                                userSpeech = "Listening..."
                                vyronReply = ""
                                currentStatus = "LISTENING"
                                initializeSpeechRecognizer()
                            }
                        }
                        "VyronSpeechID" -> {
                            android.os.Handler(mainLooper).post {
                                android.os.Handler(mainLooper).postDelayed({
                                    closeOverlay()
                                }, 1000L)
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS Error: $utteranceId")
                    android.os.Handler(mainLooper).post {
                        if (utteranceId == "VyronGreetingID") {
                            initializeSpeechRecognizer()
                        } else {
                            closeOverlay()
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS Error ($errorCode): $utteranceId")
                    android.os.Handler(mainLooper).post {
                        if (utteranceId == "VyronGreetingID") {
                            initializeSpeechRecognizer()
                        } else {
                            closeOverlay()
                        }
                    }
                }
            })

            // Mark as initialized and flush pending requests (Bug 16)
            isTtsInitialized = true
            synchronized(pendingSpeechQueue) {
                for (pendingSpeech in pendingSpeechQueue) {
                    speakReply(pendingSpeech)
                }
                pendingSpeechQueue.clear()
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
        }
    }

    private val mHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val unmuteRunnable = Runnable {
        unmuteBeep()
    }

    private fun muteBeep() {
        if (isMuted) return
        BEEP_STREAMS.forEach { stream ->
            try {
                savedOverlayVolumes[stream] = 
                    audioManager?.getStreamVolume(stream) ?: 0
                audioManager?.setStreamVolume(stream, 0, 0)
            } catch (e: Exception) {}
        }
        isMuted = true
    }

    private fun unmuteBeep() {
        if (!isMuted) return
        savedOverlayVolumes.forEach { (stream, vol) ->
            try { audioManager?.setStreamVolume(stream, vol, 0) } catch (e: Exception) {}
        }
        savedOverlayVolumes.clear()
        isMuted = false
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                currentStatus = "LISTENING"
                // Cancel backup runnable and schedule fresh unmute after 1.5 seconds
                mHandler.removeCallbacks(unmuteRunnable)
                mHandler.postDelayed(unmuteRunnable, 1500)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                currentStatus = "THINKING"
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Recognizer error: $error")
                mHandler.removeCallbacks(unmuteRunnable)
                unmuteBeep()
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        currentStatus = "IDLE"
                        userSpeech = "Didn't catch that, Boss"
                        vyronReply = "Tap mic to try again."
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy — 500ms baad retry
                        android.os.Handler(mainLooper).postDelayed({
                            try {
                                speechRecognizer?.cancel()
                            } catch (_: Exception) {}
                            startListening()
                        }, 500)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        speakReply("Microphone permission required Boss.")
                    }
                    else -> {
                        // Minor error — retry
                        android.os.Handler(mainLooper).postDelayed({
                            startListening()
                        }, 1000)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                mHandler.removeCallbacks(unmuteRunnable)
                unmuteBeep()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.size > 0) {
                    val speech = matches[0]
                    userSpeech = speech
                    processUserCommand(speech)
                } else {
                    currentStatus = "IDLE"
                    userSpeech = "Didn't catch that, Boss"
                    vyronReply = "Tap mic to try again."
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        muteBeep()
        speechRecognizer?.startListening(recognizerIntent)
        
        // Backup unmute fallback after 3 seconds in case onReadyForSpeech is delayed or fails
        mHandler.removeCallbacks(unmuteRunnable)
        mHandler.postDelayed(unmuteRunnable, 3000)
    }

    // Process intent: Dynamic router combining Instant Fallbacks + Gemini Parser
    private fun processUserCommand(command: String) {
        val cleanCommand = command.lowercase(Locale.ROOT).trim()
        val context = this

        Log.d(TAG, "Processing command: $cleanCommand")

        // 1. INSTANT REGEX Fallback triggers (0ms network delay!)
        when {
            // Flashlight triggers
            cleanCommand.contains("flashlight on") || cleanCommand.contains("torch on") || cleanCommand.contains("flashlight chalu") -> {
                FlashlightController.turnOn(context)
                speakReply("Yes Boss! Flashlight is now active.")
                return
            }
            cleanCommand.contains("flashlight off") || cleanCommand.contains("torch off") || cleanCommand.contains("flashlight band") -> {
                FlashlightController.turnOff(context)
                speakReply("Right away, Sir. The flashlight is off.")
                return
            }
            cleanCommand.contains("toggle flashlight") || cleanCommand.contains("flashlight switch") -> {
                FlashlightController.toggle(context)
                speakReply("Changed flashlight status, Boss!")
                return
            }

            // Navigation trigger
            cleanCommand.contains("home screen") || cleanCommand.contains("go home") || cleanCommand.contains("home par") -> {
                VyronAccessibilityService.instance?.let {
                    val intent = Intent(it, VyronAccessibilityService::class.java).apply {
                        action = VyronAccessibilityService.ACTION_NAVIGATE_HOME
                    }
                    it.startService(intent)
                    speakReply("Returning home, Boss.")
                } ?: run {
                    speakReply("Please enable Accessibility service first.")
                }
                return
            }

            // Screen lock trigger
            cleanCommand.contains("lock screen") || cleanCommand.contains("phone lock") || cleanCommand.contains("screen lock") -> {
                VyronAccessibilityService.instance?.let {
                    val intent = Intent(it, VyronAccessibilityService::class.java).apply {
                        action = VyronAccessibilityService.ACTION_LOCK_SCREEN
                    }
                    it.startService(intent)
                    speakReply("Locking the device securely.")
                } ?: run {
                    speakReply("Accessibility permission needed to lock device.")
                }
                return
            }

            // Power Menu trigger
            cleanCommand.contains("power menu") || cleanCommand.contains("power option") || cleanCommand.contains("switch off menu") -> {
                VyronAccessibilityService.instance?.let {
                    val intent = Intent(it, VyronAccessibilityService::class.java).apply {
                        action = VyronAccessibilityService.ACTION_POWER_MENU
                    }
                    it.startService(intent)
                    speakReply("Displaying secure system power options.")
                } ?: run {
                    speakReply("Accessibility permission needed.")
                }
                return
            }

            // Sound volume triggers
            cleanCommand.contains("volume max") || cleanCommand.contains("max volume") || cleanCommand.contains("volume full") -> {
                VolumeController.setMaxVolume(context)
                speakReply("Volume raised to 100%!")
                return
            }
            cleanCommand.contains("mute") || cleanCommand.contains("silent my phone") -> {
                VolumeController.mute(context)
                speakReply("Silence active, Boss.")
                return
            }
            cleanCommand.contains("unmute") || cleanCommand.contains("restore volume") -> {
                VolumeController.unmute(context)
                speakReply("Volume restored.")
                return
            }

            // Camera Click trigger
            cleanCommand.contains("shutter") || cleanCommand.contains("click photo") || cleanCommand.contains("camera click") -> {
                VyronAccessibilityService.instance?.let {
                    val intent = Intent(it, VyronAccessibilityService::class.java).apply {
                        action = VyronAccessibilityService.ACTION_CLICK_SHUTTER
                    }
                    it.startService(intent)
                    speakReply("Shutter click triggered!")
                } ?: run {
                    speakReply("Accessibility permission is required for shutter clicking.")
                }
                return
            }
        }

        // 2. Dynamic Gemini Classification Parser (For advanced/natural language triggers)
        Log.d(TAG, "Dispatching to Gemini AI...")
        
        val systemPrompt = """
            You are the natural language classifier for the native Android assistant 'VYRON OS'.
            Your task is to analyze the user's spoken command and map it to a clean system instruction.
            Respond strictly in the format:
            COMMAND: [intent] | VALUE: [parsed_val] | TEXT: [message_content]

            Intents can be:
            1. CAMERA_PHOTO (Take photo)
            2. OPEN_CAMERA (Launch camera app)
            3. FLIP_CAMERA (Flip camera)
            4. VOLUME_LEVEL (Value: percentage number e.g. 70)
            5. SOUND_PROFILE (Value: SILENT, VIBRATE, NORMAL)
            6. BRIGHTNESS_LEVEL (Value: percentage number e.g. 60)
            7. AUTO_BRIGHTNESS (Toggle auto brightness)
            8. SYSTEM_TIME (Time date check)
            9. BATTERY_STATUS (Battery percent check)
            10. WEATHER (City name if specified, otherwise empty)
            11. GPS_LOCATION (Exact current location check)
            12. SEND_SMS (Value: receiver name, Text: SMS text message)
            13. SEND_WHATSAPP (Value: receiver name, Text: WhatsApp text message)
            14. MAPS_NAVIGATION (Text: destination address or query)
            15. MAPS_VIEW (Text: target location)
            16. MAPS_FIND_NEARBY (Text: ATMs, petrol pumps, hospitals, restaurants)
            17. PLAY_SPOTIFY (Text: track name or query)
            18. RESUME_SPOTIFY (Resume playback)
            19. NET_TOGGLE (Value: WIFI_ON, WIFI_OFF, BT_ON, BT_OFF)
            20. LAUNCH_APP (Text: app name to launch)
            21. CALL_PHONE (Value: target contact name)
            22. REMINDER (Value: minutes/time representation, Text: reminder body)
            23. SCREEN_SCAN (Read/analyze/explain what is currently visible on the phone's screen)
            24. CLICK_ELEMENT (Click or tap a visible button or text label on the screen. Value: the exact text to click, e.g. 'login', 'next', 'submit')
            25. WEB_SEARCH (Perform a standard Google web search on the browser. Text: the search query string, e.g. 'current rate of gold in India')
            26. OPEN_CAMERA_VIDEO (Launch the phone's native camera application in video recording/camcorder mode)
            
            TONE GUIDELINES:
            Always reply to the user in an extremely friendly, helpful, warm, comforting whisper-like voice. 
            Speak as an intelligent premium companion in English/Hinglish/Hindi depending on what sounds more natural (e.g. use terms like "Sure Boss!", "Absolutely!", "Right away, Sir!", "हां बिल्कुल!", "मैं अभी करता हूँ Boss!"). Avoid robotic, short, or generic dry standard text responses; sound natural, highly conversational, and polite.
            
            If the intent matches nothing, respond with:
            COMMAND: ASSISTANT | VALUE: none | TEXT: [helpful voice response in English/Hindi/Hinglish to the user]

            User spoken command: "$command"
        """.trimIndent()

        val securePref = com.vyron.os.getSecureSharedPreferences(this)
        val apiKey = securePref.getString("gemini_api_key", "") ?: ""
        val model = GenerativeModel("gemini-2.5-flash", apiKey)
        
        // Run Coroutine for AI parsing
        lifecycleScope.launch(Dispatchers.IO) {

            try {
                val response = model.generateContent(systemPrompt).text ?: ""
                Log.d(TAG, "Gemini Response: $response")
                
                withContext(Dispatchers.Main) {
                    handleGeminiInstruction(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API error", e)
                withContext(Dispatchers.Main) {
                    val errMsg = e.localizedMessage ?: "Unknown error"
                    if (errMsg.contains("API key", ignoreCase = true) || errMsg.contains("API_KEY", ignoreCase = true) || errMsg.contains("API", ignoreCase = true)) {
                        speakReply("Invalid Gemini API Key! Please verify your key under Vyron Settings.")
                    } else {
                        speakReply("Connection issue. Please check network, Boss.")
                    }
                }
            }
        }

    }

    private fun handleGeminiInstruction(aiOutput: String) {
        val context = this
        try {
            val parts = aiOutput.split("|")
            var intent = ""
            var value = ""
            var text = ""

            for (part in parts) {
                val cleanPart = part.trim()
                when {
                    cleanPart.startsWith("COMMAND:") -> intent = cleanPart.removePrefix("COMMAND:").trim()
                    cleanPart.startsWith("VALUE:") -> value = cleanPart.removePrefix("VALUE:").trim()
                    cleanPart.startsWith("TEXT:") -> text = cleanPart.removePrefix("TEXT:").trim()
                }
            }

            when (intent) {
                "CAMERA_PHOTO" -> {
                    speakReply("Taking photo hands-free, Boss!")
                    mHandler.removeCallbacks(closeOverlayRunnable) // Cancel auto-close until photo saved (Bug 18)
                    CameraController.takePhoto(context) {
                        mHandler.post {
                            closeOverlay()
                        }
                    }
                }
                "OPEN_CAMERA" -> {
                    speakReply("Launching camera app, Sir.")
                    CameraController.openCamera(context)
                }
                "FLIP_CAMERA" -> {
                    speakReply("Flipping camera perspective.")
                    CameraController.flipCamera()
                }
                "VOLUME_LEVEL" -> {
                    val percent = value.toIntOrNull() ?: 50
                    VolumeController.setVolumeLevel(context, percent)
                    speakReply("Volume set to $percent percent, Boss.")
                }
                "SOUND_PROFILE" -> {
                    VolumeController.setSoundProfile(context, value)
                    speakReply("Sound profile switched to $value.")
                }
                "BRIGHTNESS_LEVEL" -> {
                    val percent = value.toIntOrNull() ?: 60
                    val success = BrightnessController.setBrightness(context, percent)
                    if (success) {
                        speakReply("Screen brightness adjusted to $percent percent, Sir.")
                    } else {
                        speakReply("Please grant System Write settings permission to adjust screen brightness, Boss.")
                    }
                }
                "AUTO_BRIGHTNESS" -> {
                    val success = BrightnessController.enableAutoBrightness(context)
                    if (success) {
                        speakReply("Automatic brightness enabled.")
                    } else {
                        speakReply("Please grant System Write settings permission to enable auto brightness, Boss.")
                    }
                }
                "NET_TOGGLE" -> {
                    val enabled = value.contains("ON", ignoreCase = true)
                    val resultText = if (value.contains("WIFI", ignoreCase = true)) {
                        com.vyron.os.automation.NetworkController.toggleWifi(context, enabled)
                    } else if (value.contains("BT", ignoreCase = true) || value.contains("BLUETOOTH", ignoreCase = true)) {
                        com.vyron.os.automation.NetworkController.toggleBluetooth(context, enabled)
                    } else {
                        "Invalid network toggle command, Boss."
                    }
                    speakReply(resultText)
                }
                "SCREEN_SCAN" -> {
                    val screenText = com.vyron.os.automation.VyronAccessibilityService.instance?.scanActiveScreen()
                    if (screenText.isNullOrEmpty() || screenText.contains("not accessible")) {
                        speakReply("I can't read the screen right now, Boss. Please make sure the Vyron Accessibility Service is enabled in settings.")
                    } else {
                        explainScreenToUser(screenText)
                    }
                }
                "CLICK_ELEMENT" -> {
                    val success = com.vyron.os.automation.VyronAccessibilityService.instance?.clickTextOnScreen(value) ?: false
                    if (success) {
                        speakReply("Clicked $value for you, Boss.")
                    } else {
                        speakReply("Sorry Boss, I couldn't find a clickable button named '$value' on your screen.")
                    }
                }
                "WEB_SEARCH" -> {
                    speakReply("Google pe search kar rahi hoon, Boss...")
                    try {
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8"))).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(searchIntent)
                    } catch (e: Exception) {
                        speakReply("Browser open nahi kar payi, Boss.")
                    }
                }
                "OPEN_CAMERA_VIDEO" -> {
                    speakReply("Video mode mein camera launch kar rahi hoon, Boss...")
                    try {
                        val videoIntent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(videoIntent)
                    } catch (e: Exception) {
                        speakReply("Camera video mode launch nahi kar payi, Boss.")
                    }
                }
                "SYSTEM_TIME" -> {
                    val details = SystemInfoManager.getDateTimeDetails()
                    speakReply(details)
                }
                "BATTERY_STATUS" -> {
                    val details = SystemInfoManager.getBatteryStatus(context)
                    speakReply(details)
                }
                "WEATHER" -> {
                    speakReply("Fetching local weather report, wait a second...")
                    SystemInfoManager.getWeatherReport(context, value) { report ->
                        speakReply(report)
                    }
                }
                "GPS_LOCATION" -> {
                    speakReply("Locating your exact current GPS address...")
                    SystemInfoManager.getCurrentLocationAddress(context) { address ->
                        speakReply("You are currently at: $address")
                    }
                }
                "SEND_SMS" -> {
                    speakReply("Composing SMS to $value.")
                    TelephonyAndMessaging.sendSMS(context, value, text)
                }
                "SEND_WHATSAPP" -> {
                    speakReply("Opening WhatsApp to send message to $value.")
                    TelephonyAndMessaging.sendWhatsAppMessage(context, value, text)
                }
                "MAPS_NAVIGATION" -> {
                    speakReply("Starting turn-by-turn navigation on Google Maps, Sir.")
                    TelephonyAndMessaging.startNavigation(context, text)
                }
                "MAPS_VIEW" -> {
                    speakReply("Locating place on the map.")
                    TelephonyAndMessaging.viewOnMap(context, text)
                }
                "MAPS_FIND_NEARBY" -> {
                    speakReply("Finding nearby $text instantly.")
                    TelephonyAndMessaging.findNearby(context, text)
                }
                "PLAY_SPOTIFY" -> {
                    speakReply("Searching and playing $text on Spotify!")
                    TelephonyAndMessaging.playSpotifySong(context, text)
                }
                "RESUME_SPOTIFY" -> {
                    speakReply("Resuming playback, Sir.")
                    TelephonyAndMessaging.resumeSpotifyPlayback(context)
                }
                "LAUNCH_APP" -> {
                    speakReply("Launching $text app immediately.")
                    TelephonyAndMessaging.launchApp(context, text)
                }
                "CALL_PHONE" -> {
                    speakReply("Placing cell call to $value.")
                    TelephonyAndMessaging.placePhoneCall(context, value)
                }
                "REMINDER" -> {
                    speakReply("Got it! Reminder set for $value: $text")
                    ReminderManager.setSmartReminder(context, value, text)
                }
                "ASSISTANT" -> {
                    speakReply(text)
                }
                else -> {
                    speakReply("Intent classified as $intent. I'll execute it, Boss.")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Command execute failed", e)
            speakReply("Sorry Boss, I couldn't finish the automation. " + e.localizedMessage)
        }
    }

    private fun explainScreenToUser(screenText: String) {
        currentStatus = "THINKING"
        val explainPrompt = """
            You are the voice assistant 'VYRON OS'.
            Explain to the user what is currently visible on their phone screen based on this extracted text layout tree.
            Be conversational, warm, and highly friendly, speaking in English, Hinglish, or Hindi (e.g. use "Sure Boss!", "Right here, Sir!"). 
            Summarize key notifications, open apps, or details clearly and comforting.
            
            Screen Layout Tree:
            $screenText
        """.trimIndent()
        
        val securePref = com.vyron.os.getSecureSharedPreferences(this)
        val apiKey = securePref.getString("gemini_api_key", "") ?: ""
        val model = GenerativeModel("gemini-2.5-flash", apiKey)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = model.generateContent(explainPrompt).text ?: ""
                withContext(Dispatchers.Main) {
                    speakReply(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen explanation failed", e)
                withContext(Dispatchers.Main) {
                    speakReply("Connection issue. Unable to analyze screen contents, Boss.")
                }
            }
        }
    }

    private val closeOverlayRunnable = Runnable {
        closeOverlay()
    }

    private fun speakReply(replyText: String, utteranceId: String = "VyronSpeechID") {
        if (!isTtsInitialized) {
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.add(replyText)
            }
            // Temporarily print it on overlay so the user sees it immediately
            vyronReply = replyText
            currentStatus = "THINKING"
            return
        }

        vyronReply = replyText
        currentStatus = "SPEAKING"
        unmuteBeep()
        
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(replyText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

        // Safe maximum backup safety delay of 25 seconds or proportional to length (whichever is larger) in case UtteranceProgressListener hangs
        if (utteranceId == "VyronSpeechID") {
            mHandler.removeCallbacks(closeOverlayRunnable)
            val safetyDelay = maxOf(25000L, replyText.length * 100L)
            mHandler.postDelayed(closeOverlayRunnable, safetyDelay)
        }
    }

    private fun closeOverlay() {
        mHandler.removeCallbacks(closeOverlayRunnable)
        isSheetVisible = false
        unmuteBeep()
        android.os.Handler(mainLooper).postDelayed({
            finish()
        }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacksAndMessages(null) // Prevent Handler leaks (Bug 17)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        unmuteBeep()
        
        // Resume background wake-word listening loop
        resumeBackgroundWakeService()
    }

    private fun stopBackgroundWakeService() {
        try {
            val intent = Intent(this, com.vyron.os.services.VyronWakeWordService::class.java)
            stopService(intent)
            Log.d(TAG, "Background wake word service stopped completely.")
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Failed to stop background wake service", e)
        }
    }

    private fun resumeBackgroundWakeService() {
        try {
            val intent = Intent(this, com.vyron.os.services.VyronWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Background wake word service restarted.")
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Failed to resume background wake service", e)
        }
    }


    // Compose layout for overlay UI
    @Composable
    fun OverlayHUD() {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val edgeGlowPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "edgePhase"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { closeOverlay() }, // Tap background to dismiss
            contentAlignment = Alignment.BottomCenter
        ) {
            // Screen Bezel Waving Colors (Edge Glow)
            if (currentStatus != "IDLE" && isSheetVisible) {
                // Left Edge Glow
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(14.dp)
                        .align(Alignment.CenterStart)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, Color.Transparent)
                            )
                        )
                        .drawWithContent {
                            drawContent() // Draw the horizontal gradient mask first
                            val phase = edgeGlowPhase // Active state read inside draw phase!
                            val animatedEdgeGlowBrush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6), // Purple
                                    Color(0xFF10B981), // Emerald
                                    Color(0xFF3B82F6), // Blue
                                    Color(0xFFEF4444), // Red
                                    Color(0xFF8B5CF6)
                                ),
                                start = Offset(0f, phase),
                                end = Offset(0f, phase + 1000f),
                                tileMode = TileMode.Repeated
                            )
                            drawRect(
                                brush = animatedEdgeGlowBrush,
                                blendMode = BlendMode.SrcIn
                            )
                        }
                )

                // Right Edge Glow
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(14.dp)
                        .align(Alignment.CenterEnd)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.White)
                            )
                        )
                        .drawWithContent {
                            drawContent() // Draw the horizontal gradient mask first
                            val phase = edgeGlowPhase // Active state read inside draw phase!
                            val animatedEdgeGlowBrush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6), // Purple
                                    Color(0xFF10B981), // Emerald
                                    Color(0xFF3B82F6), // Blue
                                    Color(0xFFEF4444), // Red
                                    Color(0xFF8B5CF6)
                                ),
                                start = Offset(0f, phase),
                                end = Offset(0f, phase + 1000f),
                                tileMode = TileMode.Repeated
                            )
                            drawRect(
                                brush = animatedEdgeGlowBrush,
                                blendMode = BlendMode.SrcIn
                            )
                        }
                )
            }

            AnimatedVisibility(
                visible = isSheetVisible,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350))
            ) {
                val context = LocalContext.current
                var manualTypedText by remember { mutableStateOf("") }
                val focusRequester = remember { FocusRequester() }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            // Consume clicks to prevent background click-through
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Glassmorphic Dialog Box Card above bottom pill
                    if (userSpeech.isNotEmpty() || vyronReply.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .border(1.dp, Color(0xFF1F2937).copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF070709).copy(alpha = 0.85f)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // User speech transcript
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "💬 You: ",
                                        color = Color(0xFF10B981),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = userSpeech,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }

                                if (vyronReply.isNotEmpty()) {
                                    Divider(color = Color(0xFF1F2937).copy(alpha = 0.4f), thickness = 0.5.dp)

                                    // Assistant response
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🔮 Vyron: ",
                                            color = Color(0xFF8B5CF6),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            text = vyronReply,
                                            color = Color(0xFFE5E7EB),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.SansSerif
                                        )
                                    }
                                }
                                
                                // Glowing soundwave bars at the card bottom during actions
                                if (currentStatus == "LISTENING" || currentStatus == "SPEAKING" || currentStatus == "THINKING") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(20.dp),
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (currentStatus == "LISTENING" || currentStatus == "SPEAKING") {
                                            val barCount = 5
                                            for (i in 0 until barCount) {
                                                val speed = 500 + i * 150
                                                val barScale by infiniteTransition.animateFloat(
                                                    initialValue = 0.2f,
                                                    targetValue = 1.0f,
                                                    animationSpec = infiniteRepeatable(
                                                        animation = tween(speed, easing = androidx.compose.animation.core.LinearEasing),
                                                        repeatMode = RepeatMode.Reverse
                                                    ),
                                                    label = "bar"
                                                )

                                                Box(
                                                    modifier = Modifier
                                                        .width(4.dp)
                                                        .fillMaxHeight(barScale)
                                                        .background(
                                                            color = if (currentStatus == "LISTENING") Color(0xFF10B981) else Color(0xFF8B5CF6),
                                                            shape = RoundedCornerShape(2.dp)
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                        } else if (currentStatus == "THINKING") {
                                            Text(
                                                text = "🌀 Vyron is thinking...",
                                                color = Color(0xFFF59E0B),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Floating Bottom Pill Input Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF0C0C0F))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(32.dp))
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Plus Button (+)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF1B1B22))
                                .clickable {
                                    Toast.makeText(context, "VYRON Automation Core Online", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // 2. Settings Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable {
                                    val intent = Intent(context, com.vyron.os.MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⚙️", color = Color.White, fontSize = 18.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // 3. BasicTextField (Ask Vyron...)
                        BasicTextField(
                            value = manualTypedText,
                            onValueChange = { manualTypedText = it },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (manualTypedText.trim().isNotEmpty()) {
                                        speechRecognizer?.cancel()
                                        unmuteBeep()
                                        userSpeech = manualTypedText
                                        vyronReply = ""
                                        currentStatus = "THINKING"
                                        processUserCommand(manualTypedText)
                                        manualTypedText = ""
                                    }
                                }
                            ),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.SansSerif
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .padding(vertical = 12.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            focusRequester.requestFocus()
                                        },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (manualTypedText.isEmpty()) {
                                        Text(
                                            text = "Ask Vyron...",
                                            color = Color(0xFF555566),
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.SansSerif
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // 4. Circular Microphone with pulse border
                        val micGlowPulse by infiniteTransition.animateFloat(
                            initialValue = 0.7f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "micGlow"
                        )
                        
                        val isListeningState = currentStatus == "LISTENING"

                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(
                                    color = if (isListeningState) Color(0xFF8B5CF6).copy(alpha = 0.15f) else Color.Transparent,
                                    shape = RoundedCornerShape(23.dp)
                                )
                                .border(
                                    width = if (isListeningState) (2 * micGlowPulse).dp else 1.dp,
                                    color = if (isListeningState) Color(0xFF8B5CF6) else Color(0xFF1F2937),
                                    shape = RoundedCornerShape(23.dp)
                                )
                                .clickable {
                                    if (isListeningState) {
                                        speechRecognizer?.cancel()
                                        currentStatus = "IDLE"
                                        unmuteBeep()
                                    } else {
                                        val securePref = com.vyron.os.getSecureSharedPreferences(this@VyronOverlayActivity)
                                        val apiKey = securePref.getString("gemini_api_key", "") ?: ""
                                        if (apiKey.isEmpty()) {
                                            userSpeech = "API Key Missing!"
                                            vyronReply = "Please open the Vyron OS settings in the main app to enter your personal API Key."
                                            currentStatus = "IDLE"
                                        } else {
                                            userSpeech = "Listening..."
                                            vyronReply = ""
                                            currentStatus = "LISTENING"
                                            startListening()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isListeningState) "🎙️" else "🎤",
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // 5. Send Button (➤)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (manualTypedText.trim().isNotEmpty()) Color(0xFF8B5CF6) else Color(0xFF1C1C24)
                                )
                                .clickable(enabled = manualTypedText.trim().isNotEmpty()) {
                                    if (manualTypedText.trim().isNotEmpty()) {
                                        speechRecognizer?.cancel()
                                        unmuteBeep()
                                        userSpeech = manualTypedText
                                        vyronReply = ""
                                        currentStatus = "THINKING"
                                        processUserCommand(manualTypedText)
                                        manualTypedText = ""
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "➤",
                                color = if (manualTypedText.trim().isNotEmpty()) Color.White else Color(0xFF555566),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
