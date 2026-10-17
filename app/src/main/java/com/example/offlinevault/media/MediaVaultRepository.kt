package com.example.offlinevault.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.offlinevault.data.EntryField
import com.example.offlinevault.data.EntryType
import com.example.offlinevault.data.FieldKind
import com.example.offlinevault.data.VaultDatabase
import com.example.offlinevault.data.VaultEntry
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.DataEncryptionKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

public interface MediaRepository : AutoCloseable {
    public suspend fun importUri(
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
        modifiedAt: Long? = null,
        onProgress: suspend (CopyProgress) -> Unit = {},
    ): MediaRecord

    public suspend fun getMedia(entryId: String): MediaRecord?
    public fun observeMedia(): Flow<List<MediaRecord>>
    public fun openDecrypted(entryId: String): InputStream
    public fun openSeekableDecrypted(entryId: String): SeekableByteChannel
    public fun decryptToFile(entryId: String, destination: File)
    public suspend fun deleteMedia(entryId: String): Boolean
}

/**
 * Media repository with strict separation between encrypted file bytes and EAV
 * metadata. The DEK is borrowed from the unlocked session and never persisted
 * by this class.
 */
public class MediaVaultRepository(
    context: Context,
    private val database: VaultDatabase,
    private val fieldEncryptor: AesGcmFieldEncryptor,
    dek: DataEncryptionKey,
    keyset: MediaStreamingKeyset,
    private val storage: MediaVaultStorage = MediaVaultStorage(context),
) : MediaRepository {
    private val appContext = context.applicationContext
    private val dek = dek
    private val keyset = keyset
    private val fileEncryptor = MediaFileEncryptor(keyset.primitive())
    @Volatile private var closed = false

    override suspend fun importUri(
        uri: Uri,
        displayName: String?,
        mimeType: String?,
        modifiedAt: Long?,
        onProgress: suspend (CopyProgress) -> Unit,
    ): MediaRecord {
        checkOpen()
        require(uri.scheme == ContentUriScheme) { "Media imports require a content URI" }
        storage.ensureDirectory()
        val entryId = storage.newFileName()
        val outputFile = storage.fileFor(entryId)
        val digest = MessageDigest.getInstance("SHA-256")
        val sourceLength = queryLength(uri)
        var plaintextBytes = 0L
        try {
            val source = appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected content URI")
            source.use { input ->
                DigestingInputStream(input, digest).use { digestingInput ->
                    FileOutputStream(outputFile, false).use { output ->
                        plaintextBytes = fileEncryptor.encrypt(
                            input = digestingInput,
                            output = output,
                            associatedData = fileAssociatedData(entryId),
                            totalBytes = sourceLength,
                            onProgress = onProgress,
                        ).copiedBytes
                    }
                }
            }
            val sourceDigest = digest.digest()
            try {
                verifyEncryptedFile(outputFile, entryId, plaintextBytes, sourceDigest)
                val metadata = MediaMetadata(
                    displayName = displayName?.takeIf(String::isNotBlank)
                        ?: queryDisplayName(uri)
                        ?: entryId,
                    mimeType = mimeType?.takeIf(String::isNotBlank)
                        ?: appContext.contentResolver.getType(uri),
                    sizeBytes = plaintextBytes,
                    modifiedAt = modifiedAt,
                    sourceUri = uri.toString(),
                    sha256 = sourceDigest.toHex(),
                )
                insertMetadata(entryId, metadata)
                return MediaRecord(entryId, entryId, metadata)
            } finally {
                sourceDigest.fill(0)
            }
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    override suspend fun getMedia(entryId: String): MediaRecord? {
        checkOpen()
        val entry = database.vaultEntryDao().getById(entryId) ?: return null
        if (entry.entryType != EntryType.MEDIA) return null
        val field = database.entryFieldDao().getByKey(entryId, MediaFieldKeys.METADATA) ?: return null
        val plaintext = fieldEncryptor.decrypt(
            table = ENTRY_FIELDS_TABLE,
            entryUuid = entryId,
            field = field.fieldKey,
            encrypted = com.example.offlinevault.security.EncryptedField(
                nonce = field.nonce,
                ciphertext = field.encryptedValue,
                schemaVersion = field.schemaVersion,
            ),
            dek = dek,
        )
        return try {
            MediaRecord(entryId, entryId, MediaMetadataCodec.decode(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMedia(): Flow<List<MediaRecord>> =
        database.vaultEntryDao().observeAll().flatMapLatest { entries ->
            flow {
                checkOpen()
                val records = entries
                    .filter { it.entryType == EntryType.MEDIA }
                    .mapNotNull { getMedia(it.id) }
                emit(records)
            }
        }

    override fun openDecrypted(entryId: String): InputStream {
        checkOpen()
        val file = storage.fileFor(entryId)
        require(file.isFile) { "Media ciphertext is missing" }
        return fileEncryptor.decryptingStream(
            FileInputStream(file),
            fileAssociatedData(entryId),
        )
    }

    override fun openSeekableDecrypted(entryId: String): SeekableByteChannel {
        checkOpen()
        val file = storage.fileFor(entryId)
        require(file.isFile) { "Media ciphertext is missing" }
        val encrypted = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        return try {
            fileEncryptor.decryptingChannel(encrypted, fileAssociatedData(entryId))
        } catch (error: Throwable) {
            encrypted.close()
            throw error
        }
    }

    override fun decryptToFile(entryId: String, destination: File) {
        checkOpen()
        destination.parentFile?.mkdirs()
        try {
            openDecrypted(entryId).use { input ->
                FileOutputStream(destination, false).use { output ->
                    BoundedStreamCopy.copy(input, output)
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    override suspend fun deleteMedia(entryId: String): Boolean {
        checkOpen()
        val entry = database.vaultEntryDao().getById(entryId) ?: return false
        if (entry.entryType != EntryType.MEDIA) return false
        val file = storage.fileFor(entryId)
        if (file.exists() && !file.delete()) return false
        database.vaultEntryDao().deleteById(entryId)
        return true
    }

    override fun close() {
        if (!closed) {
            closed = true
            keyset.close()
        }
    }

    private suspend fun insertMetadata(entryId: String, metadata: MediaMetadata) {
        val plaintext = MediaMetadataCodec.encode(metadata)
        val encrypted = try {
            fieldEncryptor.encrypt(
                table = ENTRY_FIELDS_TABLE,
                entryUuid = entryId,
                field = MediaFieldKeys.METADATA,
                schemaVersion = METADATA_SCHEMA_VERSION,
                plaintext = plaintext,
                dek = dek,
            )
        } finally {
            plaintext.fill(0)
        }
        try {
            database.withTransaction {
                database.vaultEntryDao().insert(VaultEntry(id = entryId, entryType = EntryType.MEDIA))
                database.entryFieldDao().insert(
                    EntryField(
                        vaultEntryId = entryId,
                        fieldKey = MediaFieldKeys.METADATA,
                        fieldKind = FieldKind.MEDIA_METADATA,
                        encryptedValue = encrypted.ciphertext,
                        nonce = encrypted.nonce,
                        schemaVersion = encrypted.schemaVersion,
                    ),
                )
            }
        } finally {
            encrypted.ciphertext.fill(0)
            encrypted.nonce.fill(0)
        }
    }

    private fun verifyEncryptedFile(
        file: File,
        entryId: String,
        expectedBytes: Long,
        expectedDigest: ByteArray,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestingOutputStream(digest)
        FileInputStream(file).use { encrypted ->
            fileEncryptor.decrypt(
                input = encrypted,
                output = sink,
                associatedData = fileAssociatedData(entryId),
            )
        }
        require(sink.count == expectedBytes) { "Encrypted media length verification failed" }
        require(MessageDigest.isEqual(expectedDigest, digest.digest())) {
            "Encrypted media digest verification failed"
        }
    }

    private fun queryLength(uri: Uri): Long? = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun fileAssociatedData(entryId: String): ByteArray =
        "offline-vault/media/$entryId/v1".encodeToByteArray()

    private fun checkOpen() = check(!closed) { "The media session is locked" }

    public companion object {
        public const val METADATA_SCHEMA_VERSION: Int = 1
        private const val ENTRY_FIELDS_TABLE: String = "entry_fields"
        private const val ContentUriScheme: String = "content"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private class DigestingOutputStream(private val digest: MessageDigest) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(value: Int) {
        digest.update(value.toByte())
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
        count += length
    }
}
