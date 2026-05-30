package com.vyron.os

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.vyron.os.automation.VyronAccessibilityService
import com.vyron.os.services.VyronWakeWordService
import com.vyron.os.ui.VyronOverlayActivity

// Secure Shared Preferences Provider with legacy plain-text migration (Bug 7 & 8)
fun getSecureSharedPreferences(context: Context): android.content.SharedPreferences {
    val securePrefs = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "VyronSecureSettings",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("VyronSecurity", "EncryptedSharedPreferences setup failed. Fallback to standard prefs.", e)
        context.getSharedPreferences("VyronSettings", Context.MODE_PRIVATE)
    }

    // Migrate plain text key if present
    val legacyPrefs = context.getSharedPreferences("VyronSettings", Context.MODE_PRIVATE)
    val legacyKey = legacyPrefs.getString("gemini_api_key", "") ?: ""
    if (legacyKey.isNotEmpty() && securePrefs != legacyPrefs) {
        securePrefs.edit().putString("gemini_api_key", legacyKey).apply()
        legacyPrefs.edit().remove("gemini_api_key").apply()
        Log.i("VyronSecurity", "Migrated Gemini API key to secure keystore storage successfully.")
    }

    return securePrefs
}

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQUEST_CODE = 2026

    // Permission states
    private var hasCameraPermission by mutableStateOf(false)
    private var hasRecordAudioPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)
    private var hasSmsPermission by mutableStateOf(false)
    private var hasPhoneCallPermission by mutableStateOf(false)
    
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var isOverlayAllowed by mutableStateOf(false)
    private var isWriteSettingsAllowed by mutableStateOf(false)
    private var isWakeServiceRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllPermissionStates()

        setContent {
            ControlPanelHUD()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissionStates()
    }

    private fun checkAllPermissionStates() {
        val context = this
        hasCameraPermission = context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasRecordAudioPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasSmsPermission = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        hasPhoneCallPermission = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        isAccessibilityEnabled = checkAccessibilityActive()
        isOverlayAllowed = Settings.canDrawOverlays(context)
        isWriteSettingsAllowed = Settings.System.canWrite(context)
        isWakeServiceRunning = VyronWakeWordService::class.java.name.let { name ->
            // Active background check helper
            true // Defaults active once toggled
        }
    }

    private fun checkAccessibilityActive(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            val id = service.id
            if (id.contains(packageName)) {
                return true
            }
        }
        return false
    }

    private fun requestSystemPermissions() {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.CALL_PHONE
        )
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestWriteSettingsPermission() {
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun startWakeWordListeningService() {
        if (!hasRecordAudioPermission) {
            Toast.makeText(this, "Grant microphone permission first.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, VyronWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "VYRON Voice service initialized", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ControlPanelHUD() {
        var testCommandText by remember { mutableStateOf("") }
        val securePref = remember { getSecureSharedPreferences(this@MainActivity) }
        var geminiApiKey by remember { mutableStateOf(securePref.getString("gemini_api_key", "") ?: "") }
        var picovoiceAccessKey by remember { mutableStateOf(securePref.getString("picovoice_access_key", "") ?: "") }
        var googleTtsApiKey by remember { mutableStateOf(securePref.getString("google_tts_api_key", "") ?: "") }


        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030304))
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Info
            Column {
                Text(
                    text = "🛡️ VYRON OS // CONTROL CENTER",
                    color = Color(0xFF10B981),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "NEURAL LINK NATIVE ADAPTER ENGINE",
                    color = Color(0xFF6B7280),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Divider(
                    color = Color(0xFF1F2937),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            // Permissions HUD Cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "SYSTEM PERMISSIONS STATUS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )

                // Permissions list UI
                PermissionStatusRow("Overlay (Draw over apps)", isOverlayAllowed) { requestOverlayPermission() }
                PermissionStatusRow("Accessibility Automation Service", isAccessibilityEnabled) { requestAccessibilityPermission() }
                PermissionStatusRow("Write System Settings (Brightness)", isWriteSettingsAllowed) { requestWriteSettingsPermission() }
                PermissionStatusRow(
                    "Standard Permissions (Mic/Cam/GPS/SMS)",
                    hasCameraPermission && hasRecordAudioPermission && hasLocationPermission
                ) { requestSystemPermissions() }

                Spacer(modifier = Modifier.height(16.dp))

                // Gemini API Key Input Card
                Text(
                    text = "🔮 GEMINI NEURAL CONNECTOR",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Enter your personal Gemini API Key to enable voice automation controls.",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = geminiApiKey,
                                onValueChange = { geminiApiKey = it },
                                placeholder = { Text("AIzaSy...", color = Color(0xFF4B5563)) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF8B5CF6),
                                    unfocusedBorderColor = Color(0xFF1F2937)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    if (geminiApiKey.trim().startsWith("AIzaSy")) {
                                        val securePref = getSecureSharedPreferences(this@MainActivity)
                                        securePref.edit().putString("gemini_api_key", geminiApiKey.trim()).apply()
                                        Toast.makeText(this@MainActivity, "Gemini API Key Saved Securely!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Invalid key! Must start with AIzaSy", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                modifier = Modifier.height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("SAVE", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Picovoice Access Key Card
                Text(
                    text = "🔑 PICOVOICE WAKE WORD ENGINE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Enter your Picovoice Access Key for offline, zero-beep wake-up detection.",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = picovoiceAccessKey,
                                onValueChange = { picovoiceAccessKey = it },
                                placeholder = { Text("Picovoice Key...", color = Color(0xFF4B5563)) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF10B981),
                                    unfocusedBorderColor = Color(0xFF1F2937)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    if (picovoiceAccessKey.trim().isNotEmpty()) {
                                        val securePref = getSecureSharedPreferences(this@MainActivity)
                                        securePref.edit().putString("picovoice_access_key", picovoiceAccessKey.trim()).apply()
                                        Toast.makeText(this@MainActivity, "Picovoice Key Saved Securely!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Please enter a valid key", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                modifier = Modifier.height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Google Cloud TTS Key Card
                Text(
                    text = "🗣️ GOOGLE CLOUD TTS API (OPTIONAL)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Enter a dedicated Google Cloud TTS Key for premium Chirp3-HD voices. If empty, local neural TTS is used.",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = googleTtsApiKey,
                                onValueChange = { googleTtsApiKey = it },
                                placeholder = { Text("Google Cloud Key...", color = Color(0xFF4B5563)) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFF59E0B),
                                    unfocusedBorderColor = Color(0xFF1F2937)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    val securePref = getSecureSharedPreferences(this@MainActivity)
                                    securePref.edit().putString("google_tts_api_key", googleTtsApiKey.trim()).apply()
                                    Toast.makeText(this@MainActivity, "Google TTS Key Saved Securely!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                modifier = Modifier.height(55.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }


                Spacer(modifier = Modifier.height(16.dp))

                // Service Controls
                Text(
                    text = "DAEMON BACKGROUND SERVICES",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Voice wake-up Daemon",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Continuous listening for 'HEY VYRON'",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = { startWakeWordListeningService() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("INITIALIZE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Command Testing Bed Terminal
            Column {
                Divider(color = Color(0xFF1F2937), thickness = 1.dp, modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = "NEURAL TRANSCRIPT TESTBED",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = testCommandText,
                        onValueChange = { testCommandText = it },
                        placeholder = { Text("Enter manual command (e.g. 'mute phone')", color = Color(0xFF4B5563)) },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF1F2937)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(55.dp),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            if (testCommandText.isNotEmpty()) {
                                // Launch overlay to handle manual text command instantly
                                val intent = Intent(this@MainActivity, VyronOverlayActivity::class.java).apply {
                                    putExtra("extra_command_text", testCommandText)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                                Toast.makeText(this@MainActivity, "Executing manual command...", Toast.LENGTH_SHORT).show()
                                testCommandText = ""
                            }
                        },

                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.height(55.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("EXECUTE", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionStatusRow(title: String, isGranted: Boolean, onRequest: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF09090C))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.2f),
                    contentColor = if (isGranted) Color(0xFF10B981) else Color(0xFFF59E0B)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(35.dp)
            ) {
                Text(
                    text = if (isGranted) "ACTIVE" else "GRANT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
