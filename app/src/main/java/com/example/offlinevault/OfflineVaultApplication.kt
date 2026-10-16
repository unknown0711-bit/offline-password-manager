package com.example.offlinevault

import android.app.Application
import com.example.offlinevault.security.TinkInitializer
import dagger.hilt.android.HiltAndroidApp
import net.zetetic.database.sqlcipher.SQLiteDatabase

@HiltAndroidApp
public class OfflineVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The new SQLCipher Android artifact requires native library loading
        // before Room opens its SupportOpenHelperFactory.
        SQLiteDatabase.loadLibs(this)
        TinkInitializer.initialize()
    }
}
