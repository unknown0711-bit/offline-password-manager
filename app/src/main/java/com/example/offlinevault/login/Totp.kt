package com.example.offlinevault.login

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public enum class TotpAlgorithm(public val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
}

public data class TotpCode(
    public val value: String,
    public val remainingSeconds: Long,
    public val periodSeconds: Long,
)

public object TotpEngine {
    public fun generate(
        secret: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / 1_000L,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
        digits: Int = 6,
        periodSeconds: Long = 30L,
    ): TotpCode {
        require(secret.isNotEmpty()) { "TOTP secret must not be empty" }
        require(digits in 6..8) { "TOTP digits must be 6 or 8" }
        require(periodSeconds > 0) { "TOTP period must be positive" }
        require(epochSeconds >= 0) { "TOTP time must not be negative" }

        val counter = epochSeconds / periodSeconds
        val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secret, algorithm.macName))
        val digest = mac.doFinal(counterBytes)
        counterBytes.fill(0)
        try {
            val offset = digest.last().toInt() and 0x0f
            val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
                ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or
                (digest[offset + 3].toInt() and 0xff)
            val modulus = when (digits) {
                6 -> 1_000_000
                else -> 100_000_000
            }
            val value = (binary % modulus).toString().padStart(digits, '0')
            val elapsed = epochSeconds % periodSeconds
            return TotpCode(value, periodSeconds - elapsed, periodSeconds)
        } finally {
            digest.fill(0)
        }
    }
}

public class TotpUriPayload(
    public val issuer: String?,
    public val label: String,
    secret: ByteArray,
    public val algorithm: TotpAlgorithm,
    public val digits: Int,
    public val periodSeconds: Long,
) : AutoCloseable {
    private var value: ByteArray? = secret.copyOf()

    public val secret: ByteArray
        get() = value?.copyOf() ?: throw IllegalStateException("TOTP payload has been closed")

    override fun close() {
        value?.fill(0)
        value = null
    }
}

/** Parser for otpauth://totp payloads emitted by authenticator QR codes. */
public object TotpUriParser {
    public fun parse(payload: String): TotpUriPayload {
        val uri = try {
            URI(payload.trim())
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid otpauth URI", error)
        }
        require(uri.scheme.equals("otpauth", ignoreCase = true)) { "Unsupported OTP URI scheme" }
        require(uri.host.equals("totp", ignoreCase = true)) { "Only TOTP QR payloads are supported" }
        val label = decode(uri.rawPath?.removePrefix("/").orEmpty())
        require(label.isNotBlank()) { "TOTP label is required" }
        val query = parseQuery(uri.rawQuery.orEmpty())
        val encodedSecret = query["secret"] ?: throw IllegalArgumentException("TOTP secret is missing")
        val secret = Base32.decode(encodedSecret)
        return try {
            val algorithm = query["algorithm"]?.let { value ->
                when (value.replace("-", "").uppercase()) {
                    "SHA1" -> TotpAlgorithm.SHA1
                    "SHA256" -> TotpAlgorithm.SHA256
                    "SHA512" -> TotpAlgorithm.SHA512
                    else -> throw IllegalArgumentException("Unsupported TOTP algorithm")
                }
            } ?: TotpAlgorithm.SHA1
            val digits = query["digits"]?.toIntOrNull() ?: 6
            val period = query["period"]?.toLongOrNull() ?: 30L
            require(digits in 6..8) { "TOTP digits must be 6 or 8" }
            require(period > 0L) { "TOTP period must be positive" }
            val issuer = query["issuer"]
            val labelIssuer = label.substringBefore(':', missingDelimiterValue = "").ifBlank { null }
            require(issuer == null || labelIssuer == null || issuer == labelIssuer) {
                "TOTP issuer does not match its label"
            }
            TotpUriPayload(issuer ?: labelIssuer, label, secret, algorithm, digits, period)
        } finally {
            secret.fill(0)
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> = rawQuery.split('&')
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "Invalid TOTP query parameter" }
            decode(pair.substring(0, separator).lowercase()) to decode(pair.substring(separator + 1))
        }
        .toMap()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

public object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    public fun decode(value: String): ByteArray {
        val normalized = value
            .filterNot { it == ' ' || it == '-' || it == '\n' || it == '\r' || it == '\t' || it == '=' }
            .uppercase()
        require(normalized.isNotEmpty()) { "Base32 value is empty" }
        var buffer = 0
        var bits = 0
        val result = ArrayList<Byte>()
        normalized.forEach { char ->
            val digit = ALPHABET.indexOf(char)
            require(digit >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or digit
            bits += 5
            if (bits >= 8) {
                bits -= 8
                result += ((buffer shr bits) and 0xff).toByte()
            }
        }
        return result.toByteArray()
    }
}
