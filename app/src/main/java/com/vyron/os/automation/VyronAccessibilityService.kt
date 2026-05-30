package com.vyron.os.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale

class VyronAccessibilityService : AccessibilityService() {

    companion object {
        var instance: VyronAccessibilityService? = null
            private set

        // Commands dispatched to the Accessibility Service from the assistant overlay
        const val ACTION_NAVIGATE_HOME = "com.vyron.os.action.NAVIGATE_HOME"
        const val ACTION_LOCK_SCREEN = "com.vyron.os.action.LOCK_SCREEN"
        const val ACTION_POWER_MENU = "com.vyron.os.action.POWER_MENU"
        const val ACTION_CLICK_SHUTTER = "com.vyron.os.action.CLICK_SHUTTER"
        const val ACTION_WHATSAPP_SEND = "com.vyron.os.action.WHATSAPP_SEND"
        const val ACTION_CALL_ACCEPT = "com.vyron.os.action.CALL_ACCEPT"
        const val ACTION_CALL_REJECT = "com.vyron.os.action.CALL_REJECT"
        const val ACTION_SCREEN_SCAN = "com.vyron.os.action.SCREEN_SCAN"
        const val ACTION_CLICK_TEXT = "com.vyron.os.action.CLICK_TEXT"

        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CONTACT = "extra_contact"
        const val EXTRA_TARGET_TEXT = "extra_target_text"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Toast.makeText(this, "VYRON OS Accessibility Service Connected", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_NAVIGATE_HOME -> goHome()
                ACTION_LOCK_SCREEN -> lockScreen()
                ACTION_POWER_MENU -> showPowerMenu()
                ACTION_CLICK_SHUTTER -> triggerCameraShutter()
                ACTION_CALL_ACCEPT -> acceptIncomingCall()
                ACTION_CALL_REJECT -> rejectIncomingCall()
                ACTION_SCREEN_SCAN -> scanActiveScreen()
                ACTION_CLICK_TEXT -> {
                    val target = intent.getStringExtra(EXTRA_TARGET_TEXT) ?: ""
                    clickTextOnScreen(target)
                }
                ACTION_WHATSAPP_SEND -> {
                    val contact = intent.getStringExtra(EXTRA_CONTACT) ?: ""
                    val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
                    startWhatsAppAutomation(contact, message)
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Monitor window status or handle automatic callbacks here
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    // Navigation: Return to Home Screen
    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Lock & Power: Lock Device Screen
    private fun lockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            Toast.makeText(this, "Lock Screen requires Android P (9.0) or higher.", Toast.LENGTH_LONG).show()
        }
    }

    // Lock & Power: Open System Power Menu Dialog
    private fun showPowerMenu() {
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    // Camera: Click Shutter hands-free if camera is open
    private fun triggerCameraShutter() {
        val rootNode = rootInActiveWindow ?: return
        
        // Comprehensive list of common IDs and Content Descriptions for camera shutter buttons
        val shutterKeywords = arrayOf(
            "shutter", "capture", "take photo", "click", "camera_button", "shutter_button",
            "photobutton", "photo button", "take_picture", "snapshot"
        )

        val success = searchAndClickShutter(rootNode, shutterKeywords)
        if (!success) {
            // Fallback: Perform a double-tap/click in the middle-bottom of the screen where most shutters are placed
            val displayMetrics = resources.displayMetrics
            val x = displayMetrics.widthPixels / 2f
            val y = displayMetrics.heightPixels * 0.85f  // Shutter is usually in middle bottom
            performClickAt(x, y)
            Toast.makeText(this, "Simulated Shutter Click at middle-bottom", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Native Shutter Click Triggered", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchAndClickShutter(node: AccessibilityNodeInfo, keywords: Array<String>): Boolean {
        val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT)

        if (node.isClickable) {
            for (keyword in keywords) {
                if (contentDesc?.contains(keyword) == true || viewId?.contains(keyword) == true) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClickShutter(child, keywords)) {
                return true
            }
        }
        return false
    }

    // Calls: Accept Incoming Calls Hands-Free
    private fun acceptIncomingCall() {
        val rootNode = rootInActiveWindow ?: return
        val acceptKeywords = arrayOf("accept", "answer", "incoming call", "pickup", "green button", "swipe up to answer")
        val success = searchAndClickShutter(rootNode, acceptKeywords)
        if (!success) {
            // General swipe gesture upward (common to answer calls on many phones)
            val displayMetrics = resources.displayMetrics
            swipeUp(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.8f, displayMetrics.heightPixels * 0.3f)
        }
    }

    // Calls: Decline/Reject Incoming Calls Hands-Free
    private fun rejectIncomingCall() {
        val rootNode = rootInActiveWindow ?: return
        val rejectKeywords = arrayOf("decline", "reject", "dismiss", "decline call", "hangup", "red button", "swipe down to reject")
        val success = searchAndClickShutter(rootNode, rejectKeywords)
        if (!success) {
            // General swipe gesture downward (common to decline calls)
            val displayMetrics = resources.displayMetrics
            swipeDown(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.4f, displayMetrics.heightPixels * 0.8f)
        }
    }

    // Messaging: Fully Autonomous WhatsApp Messaging (finding fields and sending)
    private fun startWhatsAppAutomation(contact: String, message: String) {
        val rootNode = rootInActiveWindow ?: return
        
        // 1. Look for message text box
        val textNode = findNodeByKeywords(rootNode, arrayOf("message", "type a message", "entry", "text_entry"))
        if (textNode != null) {
            // Write message text
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            textNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            
            // 2. Look for send button
            // In WhatsApp, the Send button usually appears once text is typed. We can search for the "Send" description
            android.os.Handler(mainLooper).postDelayed({
                val newRootNode = rootInActiveWindow ?: return@postDelayed
                val sendNode = findNodeByKeywords(newRootNode, arrayOf("send", "send button", "send_button", "voice note"))
                if (sendNode != null && sendNode.isClickable) {
                    sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Toast.makeText(this, "Autonomous WhatsApp Sent Successfully", Toast.LENGTH_SHORT).show()
                } else {
                    // Fallback click on typical Send button coordinates
                    val displayMetrics = resources.displayMetrics
                    performClickAt(displayMetrics.widthPixels - 80f, displayMetrics.heightPixels - 120f)
                }
            }, 300)
        } else {
            Toast.makeText(this, "Open WhatsApp Chat screen to automate sending.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findNodeByKeywords(node: AccessibilityNodeInfo, keywords: Array<String>): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT)
        val text = node.text?.toString()?.lowercase(Locale.ROOT)

        for (keyword in keywords) {
            if (contentDesc?.contains(keyword) == true || 
                viewId?.contains(keyword) == true || 
                text?.contains(keyword) == true) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByKeywords(child, keywords)
            if (found != null) return found
        }
        return null
    }

    // Helper: Perform Click Taps via Gesture Dispatcher
    private fun performClickAt(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    // Helper: Swipes for answering/declining
    private fun swipeUp(startX: Float, startY: Float, endY: Float) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun swipeDown(startX: Float, startY: Float, endY: Float) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    // Dynamic Screen-Agent: Gathers text layout tree (Bug 1 & Blurr Adaptations)
    fun scanActiveScreen(): String {
        val rootNode = rootInActiveWindow ?: return "Screen is empty or not accessible."
        val sb = java.lang.StringBuilder()
        traverseAndExtractText(rootNode, sb)
        val result = sb.toString().trim()
        return if (result.isEmpty()) "No visible text detected on screen." else result
    }

    private fun traverseAndExtractText(node: AccessibilityNodeInfo, sb: java.lang.StringBuilder) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        if (!text.isNullOrEmpty()) {
            sb.append("- Text: \"").append(text).append("\"")
            if (node.isClickable) sb.append(" [Clickable]")
            sb.append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append("- Description: \"").append(contentDesc).append("\"")
            if (node.isClickable) sb.append(" [Clickable]")
            sb.append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndExtractText(child, sb)
        }
    }

    // Dynamic Screen-Agent: Find and click specific text labels (Bug 1 & Blurr Adaptations)
    fun clickTextOnScreen(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return searchAndClickText(rootNode, targetText.lowercase(Locale.ROOT))
    }

    private fun searchAndClickText(node: AccessibilityNodeInfo, target: String): Boolean {
        val text = node.text?.toString()?.lowercase(Locale.ROOT)
        val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)

        if (node.isClickable && (text?.contains(target) == true || contentDesc?.contains(target) == true)) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) return true
        }

        // Fallback: Check if parent contains text and is clickable
        if (text?.contains(target) == true || contentDesc?.contains(target) == true) {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) return true
                }
                parent = parent.parent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClickText(child, target)) {
                return true
            }
        }
        return false
    }
}
