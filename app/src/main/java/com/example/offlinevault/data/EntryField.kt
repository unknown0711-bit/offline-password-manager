package com.example.offlinevault.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * EAV value belonging to a [VaultEntry].
 *
 * [fieldKey] is non-secret schema metadata (for example, "username",
 * "password", "card_number", or "body"). The value itself is always a blob,
 * allowing the encryption layer to preserve ciphertext and its framing without
 * converting sensitive data to a String.
 */
@Entity(
    tableName = "entry_fields",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntry::class,
            parentColumns = ["id"],
            childColumns = ["vault_entry_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["vault_entry_id"]),
        Index(value = ["vault_entry_id", "field_key"], unique = true)
    ]
)
data class EntryField(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "vault_entry_id")
    val vaultEntryId: String,

    @ColumnInfo(name = "field_key")
    val fieldKey: String,

    @ColumnInfo(name = "field_kind")
    val fieldKind: FieldKind = FieldKind.TEXT,

    @ColumnInfo(name = "encrypted_value", typeAffinity = ColumnInfo.BLOB)
    val encryptedValue: ByteArray,

    @ColumnInfo(name = "nonce", typeAffinity = ColumnInfo.BLOB)
    val nonce: ByteArray,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = 1,

    @ColumnInfo(name = "display_order")
    val displayOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
) {
    companion object {
        /** Creates a canonical, stable identifier suitable for persistence. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Non-secret presentation/storage hint; the payload remains encrypted bytes. */
enum class FieldKind(val databaseValue: String) {
    TEXT("text"),
    SECRET("secret"),
    URL("url"),
    NUMBER("number"),
    NOTE("note"),
    MEDIA_METADATA("media_metadata")
}
