package com.example.offlinevault.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Media3 DataSource backed by Tink's seekable decrypting channel. Seeking is
 * performed against the plaintext channel; ciphertext is never staged to disk.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
public class Media3VaultDataSource(
    private val repository: MediaRepository,
) : BaseDataSource(false) {
    private var dataSpec: DataSpec? = null
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var remaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val entryId = dataSpec.uri.pathSegments.lastOrNull()
            ?: throw IOException("Missing media entry id")
        val decrypting = repository.openSeekableDecrypted(entryId)
        try {
            decrypting.position(dataSpec.position)
            channel = decrypting
            uri = dataSpec.uri
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (decrypting.size() - dataSpec.position).coerceAtLeast(0L)
            } else {
                dataSpec.length
            }
            transferStarted(dataSpec)
            return remaining
        } catch (error: Throwable) {
            decrypting.close()
            throw IOException("Unable to open encrypted media", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val targetLength = if (remaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), remaining).toInt()
        }
        val target = ByteBuffer.wrap(buffer, offset, targetLength)
        val count = channel?.read(target) ?: throw IOException("Media channel is closed")
        if (count < 0) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            channel?.close()
        } finally {
            channel = null
            uri = null
            dataSpec = null
            remaining = C.LENGTH_UNSET.toLong()
            transferEnded()
        }
    }

    public class Factory(private val repository: MediaRepository) : DataSource.Factory {
        override fun createDataSource(): DataSource = Media3VaultDataSource(repository)

    }
}
