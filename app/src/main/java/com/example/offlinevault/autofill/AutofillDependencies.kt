package com.example.offlinevault.autofill

import android.content.Context
import android.content.IntentSender

/**
 * Process-local seam for the Android service. The application can install an
 * adapter backed by its unlocked [DataEncryptionKey] and Room database without
 * making the autofill service depend on UI objects.
 */
public object AutofillDependencies {
    @Volatile
    private var provider: AutofillDependencyProvider = DefaultAutofillDependencyProvider

    public fun install(provider: AutofillDependencyProvider) {
        this.provider = provider
    }

    public fun current(context: Context): AutofillDependencyProvider = provider

    /** Restores the fail-closed provider; useful after an unlock session ends. */
    public fun reset() {
        provider = DefaultAutofillDependencyProvider
    }
}

private object DefaultAutofillDependencyProvider : AutofillDependencyProvider {
    override fun vaultStore(context: Context): VaultAutofillStore = LockedAutofillStore

    override fun clientTrust(context: Context): AutofillClientTrustVerifier =
        PackageSignatureTrustPolicy(context.packageManager)

    override fun authenticationIntentSender(
        context: Context,
        request: ParsedAutofillRequest,
    ): IntentSender = AutofillAuthActivity.createIntentSender(
        context = context,
        action = AutofillAuthActivity.ACTION_UNLOCK,
    )
}

private object LockedAutofillStore : VaultAutofillStore {
    override suspend fun lookup(webDomains: Set<String>): VaultAutofillLookup = VaultAutofillLookup.Locked
}
