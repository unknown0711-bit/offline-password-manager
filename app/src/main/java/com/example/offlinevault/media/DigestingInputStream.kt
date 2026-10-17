package com.example.offlinevault.media

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Digest wrapper that never buffers more than the caller's copy buffer. */
public class DigestingInputStream(
    input: InputStream,
    private val digest: MessageDigest,
) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it >= 0) digest.update(it.toByte()) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) digest.update(buffer, offset, it) }
}
