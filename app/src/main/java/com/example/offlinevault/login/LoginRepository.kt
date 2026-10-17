package com.example.offlinevault.login

import androidx.room.withTransaction
import com.example.offlinevault.data.EntryField
import com.example.offlinevault.data.EntryType
import com.example.offlinevault.data.FieldKind
import com.example.offlinevault.data.VaultDatabase
import com.example.offlinevault.data.VaultEntry
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.Utf8Codec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository for first-class login records. The list/detail split is deliberate:
 * list projection decrypts only title, username, and tags; secrets have a
 * separate, explicit reveal method.
 */
public class LoginRepository(
    private val database: VaultDatabase,
    private val fieldEncryptor: AesGcmFieldEncryptor,
    private val dek: DataEncryptionKey,
    private val historyCipher: PasswordHistoryCipher = PasswordHistoryCipher(),
) {
    public fun observeLogins(): Flow<List<LoginSummary>> = database.vaultEntryDao().observeAll().map { entries ->
        buildList {
            entries.filter { it.entryType == EntryType.LOGIN }.forEach { entry ->
                loadSummary(entry)?.let(::add)
            }
        }
    }

    public suspend fun getLogin(entryUuid: String): LoginDetail? {
        val entry = database.vaultEntryDao().getById(entryUuid) ?: return null
        if (entry.entryType != EntryType.LOGIN) return null
        return loadDetail(entry)
    }

    /** Explicitly decrypts password/TOTP only after the detail UI asks for them. */
    public suspend fun revealSecrets(entryUuid: String): LoginSecrets? {
        val entry = database.vaultEntryDao().getById(entryUuid) ?: return null
        if (entry.entryType != EntryType.LOGIN) return null
        val fields = database.entryFieldDao().getForEntry(entryUuid)
        var password = CharArray(0)
        var totp: CharArray? = null
        val algorithm = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_ALGORITHM }
            ?.let { decryptText(entryUuid, it) }
            ?.let(::parseAlgorithm)
            ?: TotpAlgorithm.SHA1
        val digits = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_DIGITS }
            ?.let { decryptText(entryUuid, it).toIntOrNull() }
            ?.takeIf { it in 6..8 }
            ?: 6
        val period = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_PERIOD }
            ?.let { decryptText(entryUuid, it).toLongOrNull() }
            ?.takeIf { it > 0L }
            ?: 30L
        try {
            fields.firstOrNull { it.fieldKey == LoginFieldKeys.PASSWORD }?.let { field ->
                password = decryptChars(entryUuid, field)
            }
            fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP }?.let { field ->
                totp = decryptChars(entryUuid, field)
            }
            return LoginSecrets(password, totp, algorithm, digits, period)
        } finally {
            password.fill('\u0000')
            totp?.fill('\u0000')
        }
    }

    public suspend fun saveLogin(draft: LoginDraft): String {
        val entryUuid = draft.entryUuid ?: VaultEntry.newId()
        require('|' !in entryUuid && entryUuid.isNotBlank()) { "Invalid login entry UUID" }
        val password = draft.passwordCopy()
        val totp = draft.totpCopy()
        try {
            database.withTransaction {
                val entryDao = database.vaultEntryDao()
                val fieldDao = database.entryFieldDao()
                val existing = entryDao.getById(entryUuid)
                require(existing == null || existing.entryType == EntryType.LOGIN) {
                    "The selected entry is not a login"
                }
                val existingFields = fieldDao.getForEntry(entryUuid)
                val byKey = existingFields.associateBy { it.fieldKey }
                val oldPasswordField = byKey[LoginFieldKeys.PASSWORD]
                val oldPassword = oldPasswordField?.let { decryptChars(entryUuid, it) }
                try {
                    if (oldPassword != null && oldPassword.isNotEmpty() && !oldPassword.contentEquals(password)) {
                        rotatePasswordHistory(
                            dao = fieldDao,
                            entryUuid = entryUuid,
                            existingFields = existingFields,
                            currentPassword = oldPassword,
                            currentChangedAt = oldPasswordField.updatedAt,
                        )
                    }
                } finally {
                    oldPassword?.fill('\u0000')
                }

                val now = System.currentTimeMillis()
                val entry = existing?.copy(updatedAt = now) ?: VaultEntry(
                    id = entryUuid,
                    entryType = EntryType.LOGIN,
                    createdAt = now,
                    updatedAt = now,
                )
                if (existing == null) entryDao.insert(entry) else entryDao.update(entry)

                putText(fieldDao, entryUuid, byKey, LoginFieldKeys.TITLE, draft.title, FieldKind.TEXT, 0)
                putText(fieldDao, entryUuid, byKey, LoginFieldKeys.USERNAME, draft.username, FieldKind.TEXT, 1)
                putText(
                    fieldDao,
                    entryUuid,
                    byKey,
                    LoginFieldKeys.TAGS,
                    draft.tags.map(String::trim).filter(String::isNotEmpty).joinToString(LoginFieldKeys.TAG_SEPARATOR),
                    FieldKind.TEXT,
                    2,
                )
                putText(fieldDao, entryUuid, byKey, LoginFieldKeys.URL, draft.url, FieldKind.URL, 3)
                putText(fieldDao, entryUuid, byKey, LoginFieldKeys.NOTES, draft.notes, FieldKind.NOTE, 4)
                putChars(fieldDao, entryUuid, byKey, LoginFieldKeys.PASSWORD, password, FieldKind.SECRET, 5)
                if (totp == null || totp.isEmpty()) {
                    byKey[LoginFieldKeys.TOTP]?.let { fieldDao.delete(it) }
                    byKey[LoginFieldKeys.TOTP_ALGORITHM]?.let { fieldDao.delete(it) }
                    byKey[LoginFieldKeys.TOTP_DIGITS]?.let { fieldDao.delete(it) }
                    byKey[LoginFieldKeys.TOTP_PERIOD]?.let { fieldDao.delete(it) }
                } else {
                    putChars(fieldDao, entryUuid, byKey, LoginFieldKeys.TOTP, totp!!, FieldKind.SECRET, 6)
                    putText(
                        fieldDao,
                        entryUuid,
                        byKey,
                        LoginFieldKeys.TOTP_ALGORITHM,
                        draft.totpAlgorithm.name,
                        FieldKind.TEXT,
                        7,
                    )
                    putText(fieldDao, entryUuid, byKey, LoginFieldKeys.TOTP_DIGITS, draft.totpDigits.toString(), FieldKind.NUMBER, 8)
                    putText(
                        fieldDao,
                        entryUuid,
                        byKey,
                        LoginFieldKeys.TOTP_PERIOD,
                        draft.totpPeriodSeconds.toString(),
                        FieldKind.NUMBER,
                        9,
                    )
                }
            }
        } finally {
            password.fill('\u0000')
            totp?.fill('\u0000')
        }
        return entryUuid
    }

    public suspend fun deleteLogin(entryUuid: String) {
        database.vaultEntryDao().deleteById(entryUuid)
    }

    public suspend fun getPasswordHistory(entryUuid: String): List<PasswordHistoryItem> {
        val fields = database.entryFieldDao().getForEntry(entryUuid)
        return fields.asSequence()
            .mapNotNull { field -> LoginFieldKeys.historyIndex(field.fieldKey)?.let { it to field } }
            .sortedBy { it.first }
            .map { (index, field) ->
                historyCipher.decrypt(
                    entryUuid,
                    EncryptedPasswordHistory(index, field.updatedAt, field.nonce, field.encryptedValue),
                    dek,
                )
            }
            .toList()
    }

    public suspend fun healthReport(analyzer: PasswordHealthAnalyzer = PasswordHealthAnalyzer()): HealthReport {
        val inputs = loadHealthInputs()
        return try {
            analyzer.analyze(inputs)
        } finally {
            inputs.forEach(LoginHealthInput::close)
        }
    }

    private suspend fun loadHealthInputs(): List<LoginHealthInput> {
        val entries = database.vaultEntryDao().observeAll().first()
        return buildList {
            entries.filter { it.entryType == EntryType.LOGIN }.forEach { entry ->
                val fields = database.entryFieldDao().getForEntry(entry.id)
                val title = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TITLE }
                    ?.let { decryptText(entry.id, it) }
                    ?: return@forEach
                val password = fields.firstOrNull { it.fieldKey == LoginFieldKeys.PASSWORD }
                    ?.let { decryptChars(entry.id, it) }
                    ?: CharArray(0)
                try {
                    add(LoginHealthInput(entry.id, title, password, entry.updatedAt))
                } finally {
                    password.fill('\u0000')
                }
            }
        }
    }

    private suspend fun loadSummary(entry: VaultEntry): LoginSummary? {
        val fields = database.entryFieldDao().getForEntry(entry.id)
        val title = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TITLE }
            ?.let { decryptText(entry.id, it) }
            ?: return null
        val username = fields.firstOrNull { it.fieldKey == LoginFieldKeys.USERNAME }
            ?.let { decryptText(entry.id, it) }
            .orEmpty()
        val tags = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TAGS }
            ?.let { decryptText(entry.id, it) }
            ?.split(LoginFieldKeys.TAG_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return LoginSummary(
            entryUuid = entry.id,
            title = title,
            username = username,
            tags = tags,
            hasTotp = fields.any { it.fieldKey == LoginFieldKeys.TOTP },
            updatedAt = entry.updatedAt,
        )
    }

    private suspend fun loadDetail(entry: VaultEntry): LoginDetail {
        val fields = database.entryFieldDao().getForEntry(entry.id)
        fun fieldText(key: String): String = fields.firstOrNull { it.fieldKey == key }
            ?.let { decryptText(entry.id, it) }
            .orEmpty()
        return LoginDetail(
            entryUuid = entry.id,
            title = fieldText(LoginFieldKeys.TITLE),
            username = fieldText(LoginFieldKeys.USERNAME),
            tags = fieldText(LoginFieldKeys.TAGS).split(LoginFieldKeys.TAG_SEPARATOR).filter(String::isNotBlank),
            url = fieldText(LoginFieldKeys.URL),
            notes = fieldText(LoginFieldKeys.NOTES),
            updatedAt = entry.updatedAt,
            hasPassword = fields.any { it.fieldKey == LoginFieldKeys.PASSWORD },
            hasTotp = fields.any { it.fieldKey == LoginFieldKeys.TOTP },
            totpAlgorithm = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_ALGORITHM }
                ?.let { decryptText(entry.id, it) }
                ?.let(::parseAlgorithm)
                ?: TotpAlgorithm.SHA1,
            totpDigits = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_DIGITS }
                ?.let { decryptText(entry.id, it).toIntOrNull() }
                ?.takeIf { it in 6..8 }
                ?: 6,
            totpPeriodSeconds = fields.firstOrNull { it.fieldKey == LoginFieldKeys.TOTP_PERIOD }
                ?.let { decryptText(entry.id, it).toLongOrNull() }
                ?.takeIf { it > 0L }
                ?: 30L,
        )
    }

    private fun parseAlgorithm(value: String): TotpAlgorithm = when (value.uppercase()) {
        "SHA1" -> TotpAlgorithm.SHA1
        "SHA256" -> TotpAlgorithm.SHA256
        "SHA512" -> TotpAlgorithm.SHA512
        else -> TotpAlgorithm.SHA1
    }

    private fun decryptChars(entryUuid: String, field: EntryField): CharArray {
        val plaintext = decryptBytes(entryUuid, field)
        return try {
            Utf8Codec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decryptText(entryUuid: String, field: EntryField): String {
        val plaintext = decryptBytes(entryUuid, field)
        return try {
            String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decryptBytes(entryUuid: String, field: EntryField): ByteArray = fieldEncryptor.decrypt(
            table = ENTRY_FIELDS_TABLE,
            entryUuid = entryUuid,
            field = field.fieldKey,
            encrypted = com.example.offlinevault.security.EncryptedField(
                field.nonce,
                field.encryptedValue,
                field.schemaVersion,
            ),
            dek = dek,
        )

    private suspend fun putText(
        dao: com.example.offlinevault.data.EntryFieldDao,
        entryUuid: String,
        existing: Map<String, EntryField>,
        key: String,
        value: String,
        kind: FieldKind,
        order: Int,
    ) {
        putBytes(dao, entryUuid, existing, key, value.encodeToByteArray(), kind, order)
    }

    /** Keeps exactly the five newest historical passwords, newest at index 0. */
    private suspend fun rotatePasswordHistory(
        dao: com.example.offlinevault.data.EntryFieldDao,
        entryUuid: String,
        existingFields: List<EntryField>,
        currentPassword: CharArray,
        currentChangedAt: Long,
    ) {
        val historyFields = existingFields.mapNotNull { field ->
            LoginFieldKeys.historyIndex(field.fieldKey)?.let { it to field }
        }.sortedBy { it.first }
        val oldItems = mutableListOf<PasswordHistoryItem>()
        try {
            historyFields.forEach { (index, field) ->
                oldItems += historyCipher.decrypt(
                    entryUuid,
                    EncryptedPasswordHistory(index, field.updatedAt, field.nonce, field.encryptedValue),
                    dek,
                )
            }
            historyFields.forEach { (_, field) -> dao.delete(field) }

            val retained = ArrayList<PasswordHistoryItem>(minOf(5, oldItems.size + 1))
            retained += PasswordHistoryItem(0, currentChangedAt, currentPassword)
            oldItems.take(4).forEach { item ->
                val value = item.password
                try {
                    retained += PasswordHistoryItem(0, item.changedAt, value)
                } finally {
                    value.fill('\u0000')
                }
            }
            try {
                retained.forEachIndexed { newIndex, item ->
                    val value = item.password
                    try {
                        val encrypted = historyCipher.encrypt(
                            entryUuid = entryUuid,
                            index = newIndex,
                            password = value,
                            changedAt = item.changedAt,
                            dek = dek,
                        )
                        try {
                            dao.insert(
                                EntryField(
                                    id = EntryField.newId(),
                                    vaultEntryId = entryUuid,
                                    fieldKey = LoginFieldKeys.historyKey(newIndex),
                                    fieldKind = FieldKind.SECRET,
                                    encryptedValue = encrypted.ciphertext.copyOf(),
                                    nonce = encrypted.nonce.copyOf(),
                                    schemaVersion = HISTORY_SCHEMA_VERSION,
                                    displayOrder = 100 + newIndex,
                                    createdAt = item.changedAt,
                                    updatedAt = item.changedAt,
                                ),
                            )
                        } finally {
                            encrypted.ciphertext.fill(0)
                            encrypted.nonce.fill(0)
                        }
                    } finally {
                        value.fill('\u0000')
                    }
                }
            } finally {
                retained.forEach(PasswordHistoryItem::close)
            }
        } finally {
            oldItems.forEach(PasswordHistoryItem::close)
        }
    }

    private suspend fun putBytes(
        dao: com.example.offlinevault.data.EntryFieldDao,
        entryUuid: String,
        existing: Map<String, EntryField>,
        key: String,
        value: ByteArray,
        kind: FieldKind,
        order: Int,
    ) {
        try {
            val encrypted = fieldEncryptor.encrypt(
                table = ENTRY_FIELDS_TABLE,
                entryUuid = entryUuid,
                field = key,
                schemaVersion = FIELD_SCHEMA_VERSION,
                plaintext = value,
                dek = dek,
            )
            dao.insert(
                EntryField(
                    id = existing[key]?.id ?: EntryField.newId(),
                    vaultEntryId = entryUuid,
                    fieldKey = key,
                    fieldKind = kind,
                    encryptedValue = encrypted.ciphertext,
                    nonce = encrypted.nonce,
                    schemaVersion = encrypted.schemaVersion,
                    displayOrder = order,
                ),
            )
        } finally {
            value.fill(0)
        }
    }

    private suspend fun putChars(
        dao: com.example.offlinevault.data.EntryFieldDao,
        entryUuid: String,
        existing: Map<String, EntryField>,
        key: String,
        value: CharArray,
        kind: FieldKind,
        order: Int,
    ) {
        val bytes = utf8Bytes(value)
        try {
            putBytes(dao, entryUuid, existing, key, bytes, kind, order)
        } finally {
            bytes.fill(0)
        }
    }

    private fun utf8Bytes(value: CharArray): ByteArray = Utf8Codec.encode(value)

    private companion object {
        const val ENTRY_FIELDS_TABLE = "entry_fields"
        const val FIELD_SCHEMA_VERSION = 1
        const val HISTORY_SCHEMA_VERSION = 1
    }
}
