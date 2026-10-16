package com.example.offlinevault.data

import android.content.Context
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.SqlCipherKeyDeriver

/**
 * Opens the database only after an unlock flow supplies a DEK. There is no
 * startup fallback key and no Hilt binding that could accidentally create a
 * vault with a random or plaintext passphrase.
 */
public class VaultDatabaseFactory(
    context: Context,
    private val keyDeriver: SqlCipherKeyDeriver,
) {
    private val appContext = context.applicationContext

    public fun open(dek: DataEncryptionKey): VaultDatabase =
        VaultDatabase.create(appContext, dek, keyDeriver)
}
