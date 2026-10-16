package com.example.offlinevault.security

import com.google.crypto.tink.config.TinkConfig
import java.security.GeneralSecurityException

/**
 * Idempotent process-wide registration of Tink primitives.
 *
 * Call this from Application.onCreate before constructing any Tink primitive.
 * TinkConfig.register() is the stable registration entry point for the Android
 * Tink releases supported by this scaffold; it does not require an Android
 * Context and must be called after the Tink dependency is wired into Gradle.
 */
public object TinkInitializer {
    @Volatile
    private var initialized: Boolean = false

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    public fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                TinkConfig.register()
                initialized = true
            }
        }
    }
}
