package com.example.offlinevault.media

import com.google.crypto.tink.StreamingAead
import java.io.InputStream
import java.io.OutputStream

/** Tink StreamingAead is the only file cipher used by the media vault. */
public class MediaFileEncryptor(private val streamingAead: StreamingAead) {
    public suspend fun encrypt(
        input: InputStream,
        output: OutputStream,
        associatedData: ByteArray,
        totalBytes: Long?,
        onProgress: suspend (CopyProgress) -> Unit,
    ): CopyResult = streamingAead.newEncryptingStream(output, associatedData).use { encrypted ->
        BoundedStreamCopy.copySuspending(input, encrypted, totalBytes, onProgress = onProgress)
    }

    public fun decrypt(
        input: InputStream,
        output: OutputStream,
        associatedData: ByteArray,
        totalBytes: Long? = null,
        onProgress: (CopyProgress) -> Unit = {},
    ): CopyResult = streamingAead.newDecryptingStream(input, associatedData).use { decrypted ->
        BoundedStreamCopy.copy(decrypted, output, totalBytes, onProgress = onProgress)
    }

    public fun decryptingStream(input: InputStream, associatedData: ByteArray): InputStream =
        streamingAead.newDecryptingStream(input, associatedData)

    public fun decryptingChannel(
        input: java.nio.channels.SeekableByteChannel,
        associatedData: ByteArray,
    ): java.nio.channels.SeekableByteChannel =
        streamingAead.newSeekableDecryptingChannel(input, associatedData)
}
