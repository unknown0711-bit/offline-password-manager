package com.example.offlinevault.media

import android.content.Context
import com.example.offlinevault.data.VaultDatabase
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.DataEncryptionKey

public class MediaRepositoryFactory(
    context: Context,
    private val fieldEncryptor: AesGcmFieldEncryptor,
    private val keysetStore: MediaKeysetStore,
) {
    private val appContext = context.applicationContext

    public fun open(database: VaultDatabase, dek: DataEncryptionKey): MediaRepository {
        val keyset = keysetStore.open(dek)
        return try {
            MediaVaultRepository(
                context = appContext,
                database = database,
                fieldEncryptor = fieldEncryptor,
                dek = dek,
                keyset = keyset,
            )
        } catch (error: Throwable) {
            keyset.close()
            throw error
        }
    }
}
