package com.example.offlinevault

import android.app.Application
import com.example.offlinevault.security.TinkInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
public class OfflineVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // sqlcipher-android 4.6.x exposes the native library through the
        // Android AAR rather than the legacy SQLiteDatabase.loadLibs(Context)
        // helper. Load the same lib explicitly before Room can call nativeOpen.
        SqlCipherInitializer.initialize()
        TinkInitializer.initialize()
    }
}

/** Process-wide native SQLCipher loader for net.zetetic:sqlcipher-android. */
internal object SqlCipherInitializer {
    @Volatile
    private var initialized: Boolean = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                System.loadLibrary("sqlcipher")
                initialized = true
            }
        }
    }
}
