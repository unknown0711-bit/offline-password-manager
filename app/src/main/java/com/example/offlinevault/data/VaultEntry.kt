package com.example.offlinevault.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * The non-secret record that owns a set of encrypted [EntryField] values.
 *
 * The entry type is intentionally metadata. Names, credentials, card data, and
 * note contents belong in [EntryField.encryptedValue] rather than in String
 * columns.
 */
@Entity(
    tableName = "vault_entries",
    indices = [
        Index(value = ["entry_type"]),
        Index(value = ["updated_at"])
    ]
)
data class VaultEntry(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "entry_type")
    val entryType: EntryType,

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

/** Supported first-class record types. Custom fields remain possible for each type. */
enum class EntryType(val databaseValue: String) {
    LOGIN("login"),
    CARD("card"),
    SECURE_NOTE("secure_note")
}
