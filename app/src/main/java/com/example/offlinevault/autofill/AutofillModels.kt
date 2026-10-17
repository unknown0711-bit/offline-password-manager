package com.example.offlinevault.autofill

import android.content.ComponentName
import android.view.autofill.AutofillId

/** The IDs in the client form that can receive a login value. */
public data class AutofillFieldIds(
    public val username: List<AutofillId>,
    public val password: List<AutofillId>,
) {
    public val all: List<AutofillId>
        get() = (username + password).distinct()

    public val isEmpty: Boolean
        get() = username.isEmpty() && password.isEmpty()
}

/** The non-secret request facts used by the service and its adapters. */
public data class ParsedAutofillRequest(
    public val fieldIds: AutofillFieldIds,
    public val webDomains: Set<String>,
    public val clientPackageName: String?,
    public val clientActivity: ComponentName?,
)

/** A single login projection returned by a vault adapter. */
public class AutofillCredential(
    public val entryId: String,
    public val title: String,
    public val username: String,
    password: CharArray,
) : AutoCloseable {
    private var passwordValue: CharArray? = password.copyOf()

    public fun passwordCopy(): CharArray =
        passwordValue?.copyOf() ?: throw IllegalStateException("Autofill credential has been closed")

    override fun close() {
        passwordValue?.fill('\u0000')
        passwordValue = null
    }
}

/** A vault lookup result deliberately distinguishes a locked vault from an empty one. */
public sealed interface VaultAutofillLookup : AutoCloseable {
    public data object Locked : VaultAutofillLookup {
        override fun close() = Unit
    }

    public class Unlocked(
        public val credentials: List<AutofillCredential>,
    ) : VaultAutofillLookup {
        override fun close() {
            credentials.forEach(AutofillCredential::close)
        }
    }
}

/**
 * Narrow adapter boundary for the encrypted Phase 1 vault.
 *
 * Implementations own no database or DEK lifecycle unless explicitly documented
 * by their owner. A lookup owns its returned credentials and must be closed.
 */
public interface VaultAutofillStore {
    public suspend fun lookup(webDomains: Set<String>): VaultAutofillLookup
}

/** Supplies service dependencies without coupling an Android service to a UI or DI graph. */
public interface AutofillDependencyProvider {
    public fun vaultStore(context: android.content.Context): VaultAutofillStore

    public fun clientTrust(context: android.content.Context): AutofillClientTrustVerifier

    /** Creates the activity handoff used by the locked presentation. */
    public fun authenticationIntentSender(
        context: android.content.Context,
        request: ParsedAutofillRequest,
    ): android.content.IntentSender
}
