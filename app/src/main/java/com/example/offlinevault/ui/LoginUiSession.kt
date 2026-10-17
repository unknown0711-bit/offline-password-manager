package com.example.offlinevault.ui

import android.content.Context
import com.example.offlinevault.login.LoginRepository

/** Supplies an unlocked repository to the Phase 2 UI without retaining the DEK in Compose. */
public fun interface LoginUiSessionProvider {
    public fun repository(context: Context): LoginRepository?
}

public object LoginUiSession {
    @Volatile
    private var provider: LoginUiSessionProvider = LoginUiSessionProvider { null }

    public fun install(provider: LoginUiSessionProvider) {
        this.provider = provider
    }

    public fun repository(context: Context): LoginRepository? = provider.repository(context)

    /** Called by the vault session coordinator when the DEK/database are closed. */
    public fun lock() {
        provider = LoginUiSessionProvider { null }
        SensitiveClipboardRegistry.clearOnVaultLock()
    }
}
