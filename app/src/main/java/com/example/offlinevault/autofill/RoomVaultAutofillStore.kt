package com.example.offlinevault.autofill

import com.example.offlinevault.data.EntryField
import com.example.offlinevault.data.EntryType
import com.example.offlinevault.data.VaultDatabase
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.EncryptedField
import com.example.offlinevault.security.Utf8Codec
import kotlinx.coroutines.flow.first

/**
 * Phase 1 Room/crypto adapter. It reads only login fields needed for autofill
 * and never exposes Room entities to the Android service.
 */
public class RoomVaultAutofillStore(
    private val database: VaultDatabase,
    private val fieldEncryptor: AesGcmFieldEncryptor,
    private val dek: DataEncryptionKey,
    private val publicSuffixList: PublicSuffixList,
) : VaultAutofillStore {
    override suspend fun lookup(webDomains: Set<String>): VaultAutofillLookup {
        if (dek.isClosed) return VaultAutofillLookup.Locked
        val requestedSites = webDomains.mapNotNull(publicSuffixList::etldPlusOne).toSet()
        if (requestedSites.isEmpty()) return VaultAutofillLookup.Unlocked(emptyList())

        val credentials = buildList<AutofillCredential> {
            database.vaultEntryDao().observeAll().first()
                .asSequence()
                .filter { it.entryType == EntryType.LOGIN }
                .forEach { entry ->
                    val fields = runCatching { database.entryFieldDao().getForEntry(entry.id) }.getOrNull()
                        ?: return@forEach
                    val values: MutableMap<String, CharArray> = decryptFields(entry.id, fields)
                    try {
                        val storedUrl = values[URL_KEY]?.concatToString()
                        val storedSite = storedUrl?.let(publicSuffixList::etldPlusOne)
                        if (storedSite == null || storedSite !in requestedSites) return@forEach
                        val password = values[PASSWORD_KEY]?.copyOf()
                        if (password == null) return@forEach
                        add(
                            AutofillCredential(
                                entryId = entry.id,
                                title = values[TITLE_KEY]?.concatToString().orEmpty().ifBlank { "Vault login" },
                                username = values[USERNAME_KEY]?.concatToString().orEmpty(),
                                password = password,
                            ),
                        )
                        password.fill('\u0000')
                    } finally {
                        values.values.forEach { it.fill('\u0000') }
                    }
                }
        }
        return VaultAutofillLookup.Unlocked(credentials)
    }

    private fun decryptFields(entryId: String, fields: List<EntryField>): MutableMap<String, CharArray> =
        buildMap<String, CharArray> {
            fields.filter { it.fieldKey in REQUIRED_KEYS }.forEach { field ->
                val value = runCatching {
                    val plaintext = fieldEncryptor.decrypt(
                        table = ENTRY_FIELDS_TABLE,
                        entryUuid = entryId,
                        field = field.fieldKey,
                        encrypted = EncryptedField(field.nonce, field.encryptedValue, field.schemaVersion),
                        dek = dek,
                    )
                    try {
                        Utf8Codec.decode(plaintext)
                    } finally {
                        plaintext.fill(0)
                    }
                }.getOrNull()
                if (value != null) put(field.fieldKey, value)
            }
        }.toMutableMap()

    private companion object {
        const val ENTRY_FIELDS_TABLE = "entry_fields"
        const val TITLE_KEY = "title"
        const val USERNAME_KEY = "username"
        const val PASSWORD_KEY = "password"
        const val URL_KEY = "url"
        val REQUIRED_KEYS = setOf(TITLE_KEY, USERNAME_KEY, PASSWORD_KEY, URL_KEY)
    }
}
