package com.example.offlinevault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.SqlCipherKeyDeriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [VaultEntry::class, EntryField::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultEntryDao(): VaultEntryDao

    abstract fun entryFieldDao(): EntryFieldDao

    private var retainedSqlCipherPassphrase: ByteArray? = null

    internal fun retainSqlCipherPassphrase(passphrase: ByteArray) {
        retainedSqlCipherPassphrase?.fill(0)
        retainedSqlCipherPassphrase = passphrase
    }

    override fun close() {
        try {
            super.close()
        } finally {
            retainedSqlCipherPassphrase?.fill(0)
            retainedSqlCipherPassphrase = null
        }
    }

    companion object {
        const val DATABASE_NAME: String = "offline_vault.db"

        /**
         * Builds an encrypted Room database using SQLCipher's support factory.
         *
         * The passphrase is deliberately accepted only as bytes. The caller
         * owns its lifecycle and should clear/release it when its key flow
         * permits; this method never converts it to a String.
         */
        fun create(context: Context, passphrase: ByteArray): VaultDatabase {
            require(passphrase.isNotEmpty()) { "The vault passphrase must not be empty" }

            // SupportOpenHelperFactory retains this byte array for the lifetime
            // of its helper. It is derived key material, not the root DEK; the
            // factory must retain it until RoomDatabase.close().
            val factoryPassphrase = passphrase.copyOf()
            val factory = SupportOpenHelperFactory(factoryPassphrase)
            val database = Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                DATABASE_NAME,
            )
                .openHelperFactory(factory)
                .build()
            database.retainSqlCipherPassphrase(factoryPassphrase)
            return database
        }

        /** Derives a purpose-bound SQLCipher passphrase from the unlocked DEK. */
        fun create(context: Context, dek: DataEncryptionKey, keyDeriver: SqlCipherKeyDeriver): VaultDatabase {
            val passphrase = keyDeriver.derive(dek)
            return try {
                create(context, passphrase)
            } finally {
                passphrase.fill(0)
            }
        }
    }
}
