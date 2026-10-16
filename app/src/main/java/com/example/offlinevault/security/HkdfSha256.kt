package com.example.offlinevault.security

import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 HKDF-SHA-256. */
public object HkdfSha256 {
    private const val HASH_BYTES: Int = 32
    private const val MAX_OUTPUT_BYTES: Int = 255 * HASH_BYTES

    /**
     * Derives [outputLengthBytes] from [ikm]. A null or empty salt is treated as
     * RFC 5869's all-zero salt. [info] is domain separation context and should
     * be a stable, non-secret label such as "offlinevault/field".
     */
    @Throws(GeneralSecurityException::class)
    public fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        outputLengthBytes: Int = AES_256_KEY_BYTES,
    ): ByteArray {
        require(outputLengthBytes in 1..MAX_OUTPUT_BYTES) {
            "HKDF output must be between 1 and $MAX_OUTPUT_BYTES bytes"
        }

        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_BYTES) else salt.copyOf()
        val prk = try {
            hmac(actualSalt, ikm)
        } finally {
            actualSalt.fill(0)
        }

        val result = ByteArray(outputLengthBytes)
        var previous = ByteArray(0)
        var written = 0
        try {
            for (counter in 1..((outputLengthBytes + HASH_BYTES - 1) / HASH_BYTES)) {
                val input = ByteArray(previous.size + info.size + 1)
                previous.copyInto(input)
                info.copyInto(input, destinationOffset = previous.size)
                input[input.lastIndex] = counter.toByte()
                val block = try {
                    hmac(prk, input)
                } finally {
                    input.fill(0)
                }

                val toCopy = minOf(block.size, outputLengthBytes - written)
                block.copyInto(result, destinationOffset = written, endIndex = toCopy)
                written += toCopy
                previous.fill(0)
                previous = block
            }
            return result
        } finally {
            prk.fill(0)
            previous.fill(0)
        }
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}
