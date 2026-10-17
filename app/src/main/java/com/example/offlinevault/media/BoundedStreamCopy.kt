package com.example.offlinevault.media

import java.io.InputStream
import java.io.OutputStream

public data class CopyProgress(
    public val copiedBytes: Long,
    public val totalBytes: Long?,
) {
    public val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (copiedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

public data class CopyResult(public val copiedBytes: Long)

/** All file copies use a fixed-size, wiped buffer and never materialize a file. */
public object BoundedStreamCopy {
    public const val DEFAULT_BUFFER_SIZE: Int = 64 * 1024
    public const val MAX_BUFFER_SIZE: Int = 64 * 1024

    public fun copy(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onProgress: (CopyProgress) -> Unit = {},
    ): CopyResult {
        validate(bufferSize, totalBytes)
        val buffer = ByteArray(bufferSize)
        var copied = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied += count
                onProgress(CopyProgress(copied, totalBytes))
            }
            return CopyResult(copied)
        } finally {
            buffer.fill(0)
        }
    }

    public suspend fun copySuspending(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onProgress: suspend (CopyProgress) -> Unit = {},
    ): CopyResult {
        validate(bufferSize, totalBytes)
        val buffer = ByteArray(bufferSize)
        var copied = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied += count
                onProgress(CopyProgress(copied, totalBytes))
            }
            return CopyResult(copied)
        } finally {
            buffer.fill(0)
        }
    }

    private fun validate(bufferSize: Int, totalBytes: Long?) {
        require(bufferSize in 1..MAX_BUFFER_SIZE) { "Buffer size must be bounded" }
        require(totalBytes == null || totalBytes >= 0L) { "Total bytes must not be negative" }
    }
}
