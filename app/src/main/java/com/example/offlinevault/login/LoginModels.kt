package com.example.offlinevault.login

/** Stable field names used by the login EAV record. */
public object LoginFieldKeys {
    public const val TITLE: String = "title"
    public const val USERNAME: String = "username"
    public const val PASSWORD: String = "password"
    public const val TOTP: String = "totp"
    public const val TOTP_ALGORITHM: String = "totp_algorithm"
    public const val TOTP_DIGITS: String = "totp_digits"
    public const val TOTP_PERIOD: String = "totp_period"
    public const val TAGS: String = "tags"
    public const val URL: String = "url"
    public const val NOTES: String = "notes"
    public const val PASSWORD_HISTORY_PREFIX: String = "password_history_"
    public const val TAG_SEPARATOR: String = "\u001f"

    public fun historyKey(index: Int): String {
        require(index >= 0) { "History index must not be negative" }
        return "$PASSWORD_HISTORY_PREFIX$index"
    }

    public fun historyIndex(fieldKey: String): Int? {
        if (!fieldKey.startsWith(PASSWORD_HISTORY_PREFIX)) return null
        return fieldKey.removePrefix(PASSWORD_HISTORY_PREFIX).toIntOrNull()?.takeIf { it >= 0 }
    }
}

/** Decrypted, non-secret data suitable for the vault list. */
public data class LoginSummary(
    public val entryUuid: String,
    public val title: String,
    public val username: String,
    public val tags: List<String>,
    public val hasTotp: Boolean,
    public val updatedAt: Long,
)

/** Decrypted metadata for a detail screen. Secrets are intentionally absent. */
public data class LoginDetail(
    public val entryUuid: String,
    public val title: String,
    public val username: String,
    public val tags: List<String>,
    public val url: String,
    public val notes: String,
    public val updatedAt: Long,
    public val hasPassword: Boolean,
    public val hasTotp: Boolean,
    public val totpAlgorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    public val totpDigits: Int = 6,
    public val totpPeriodSeconds: Long = 30L,
)

/** A transient secret snapshot. The owner must call [close] when it leaves the UI. */
public class LoginSecrets(
    password: CharArray,
    totpSecret: CharArray?,
    public val totpAlgorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    public val totpDigits: Int = 6,
    public val totpPeriodSeconds: Long = 30L,
) : AutoCloseable {
    private var passwordValue: CharArray? = password.copyOf()
    private var totpValue: CharArray? = totpSecret?.copyOf()

    public val password: CharArray
        get() = passwordValue?.copyOf() ?: throw IllegalStateException("Secrets have been closed")

    public val totpSecret: CharArray?
        get() = totpValue?.copyOf()

    public val isClosed: Boolean
        get() = passwordValue == null && totpValue == null

    override fun close() {
        passwordValue?.fill('\u0000')
        totpValue?.fill('\u0000')
        passwordValue = null
        totpValue = null
    }
}

/** Input owned by a save operation. It copies secret arrays at the boundary. */
public class LoginDraft(
    public val entryUuid: String?,
    public val title: String,
    public val username: String,
    password: CharArray,
    totpSecret: CharArray? = null,
    public val tags: List<String> = emptyList(),
    public val url: String = "",
    public val notes: String = "",
    public val totpAlgorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    public val totpDigits: Int = 6,
    public val totpPeriodSeconds: Long = 30L,
) : AutoCloseable {
    private var passwordValue: CharArray? = password.copyOf()
    private var totpValue: CharArray? = totpSecret?.copyOf()

    init {
        require(title.isNotBlank()) { "A login title is required" }
        require(entryUuid?.contains('|') != true) { "Entry UUID must not contain |" }
        require(totpDigits in 6..8) { "TOTP digits must be 6 or 8" }
        require(totpPeriodSeconds > 0L) { "TOTP period must be positive" }
    }

    public fun passwordCopy(): CharArray =
        passwordValue?.copyOf() ?: throw IllegalStateException("Draft has been closed")

    public fun totpCopy(): CharArray? = totpValue?.copyOf()

    override fun close() {
        passwordValue?.fill('\u0000')
        totpValue?.fill('\u0000')
        passwordValue = null
        totpValue = null
    }
}

/** One decrypted password-history item. The owner must call [close]. */
public class PasswordHistoryItem(
    public val index: Int,
    public val changedAt: Long,
    password: CharArray,
) : AutoCloseable {
    private var value: CharArray? = password.copyOf()

    public val password: CharArray
        get() = value?.copyOf() ?: throw IllegalStateException("History item has been closed")

    override fun close() {
        value?.fill('\u0000')
        value = null
    }
}

/** Password input for the pure health analyzer. The analyzer never retains it. */
public class LoginHealthInput(
    public val entryUuid: String,
    public val title: String,
    password: CharArray,
    public val updatedAt: Long,
) : AutoCloseable {
    private var value: CharArray? = password.copyOf()

    public val password: CharArray
        get() = value?.copyOf() ?: throw IllegalStateException("Health input has been closed")

    override fun close() {
        value?.fill('\u0000')
        value = null
    }
}
