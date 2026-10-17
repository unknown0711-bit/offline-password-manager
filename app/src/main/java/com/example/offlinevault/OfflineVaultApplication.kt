package com.example.offlinevault

import android.app.Application
import com.example.offlinevault.security.TinkInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
public class OfflineVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // net.zetetic:sqlcipher-android 4.6.x does not expose the legacy
        // SQLiteDatabase.loadLibs(Context) helper. Its AAR ships libsqlcipher,
        // so load that native library before Room can invoke nativeOpen().
        SqlCipherNativeLoader.initialize()
        TinkInitializer.initialize()
    }
}

/** Idempotent process-wide native SQLCipher initialization. */
internal object SqlCipherNativeLoader {
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
