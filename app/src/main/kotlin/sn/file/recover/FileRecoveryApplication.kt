package sn.file.recover

import android.app.Application
import com.google.android.gms.ads.MobileAds
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FileRecoveryApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Mobile Ads SDK
        MobileAds.initialize(this) { }
    }
}