package com.example.offlinevault

import android.app.Application
import com.example.offlinevault.security.TinkInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
public class OfflineVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // sqlcipher-android loads its native library through the support
        // helper when Room first opens the database.
        TinkInitializer.initialize()
    }
}
