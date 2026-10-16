package com.example.offlinevault.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** BIP39 validation receives transient character arrays, never a phrase String. */
public fun interface Bip39MnemonicValidator {
    public fun isValid(words: Array<CharArray>): Boolean
}

/**
 * BIP39 English validator. The word list is public application data; phrase
 * words remain character arrays during validation and derivation.
 */
public class Bip39WordListValidator(wordList: List<String>) : Bip39MnemonicValidator {
    private val words: Array<CharArray> = wordList.map { it.toCharArray() }.toTypedArray()

    init {
        require(words.size == BIP39_WORD_COUNT_DICTIONARY) {
            "A BIP39 word list must contain 2048 words"
        }
        require(words.all { it.isNotEmpty() }) { "BIP39 words must not be empty" }
        require(words.map { it.concatToString() }.toSet().size == words.size) {
            "BIP39 words must be unique"
        }
    }

    override fun isValid(candidate: Array<CharArray>): Boolean {
        if (candidate.size != BIP39_WORD_COUNT_PHRASE) return false
        val bitBytes = ByteArray(BIP39_ENTROPY_BYTES + 1)
        return try {
            var bitPosition = 0
            for (word in candidate) {
                val index = words.indexOfFirst { it.contentEquals(word) }
                if (index < 0) return false
                for (bit in 10 downTo 0) {
                    if (((index ushr bit) and 1) != 0) {
                        bitBytes[bitPosition / 8] =
                            (bitBytes[bitPosition / 8].toInt() or
                                (1 shl (7 - (bitPosition % 8)))).toByte()
                    }
                    bitPosition++
                }
            }

            val entropy = bitBytes.copyOfRange(0, BIP39_ENTROPY_BYTES)
            try {
                val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0]
                val actualChecksum = bitBytes[BIP39_ENTROPY_BYTES]
                expectedChecksum == actualChecksum
            } finally {
                entropy.fill(0)
            }
        } finally {
            bitBytes.fill(0)
        }
    }
}

public class Bip39WrappedDek(
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    public val nonce: ByteArray = nonce.copyOf()
    public val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(this.nonce.size == AES_GCM_NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
    }
}

/**
 * Wraps a DEK with BIP39's PBKDF2-HMAC-SHA512 seed derivation and AES-256-GCM.
 * The public API is CharArray-only. The phrase and optional passphrase are
 * copied, used, and wiped before returning.
 */
public class Bip39RecoveryPhraseWrapper(
    private val validator: Bip39MnemonicValidator,
    private val random: SecureRandom = SecureRandom(),
) : PasswordDekWrapper<Bip39WrappedDek> {
    override fun wrap(dek: DataEncryptionKey, password: CharArray): Bip39WrappedDek =
        wrap(dek, password, CharArray(0))

    public fun wrap(
        dek: DataEncryptionKey,
        recoveryPhrase: CharArray,
        bip39Passphrase: CharArray,
    ): Bip39WrappedDek {
        val key = deriveKey(recoveryPhrase, bip39Passphrase)
        return try {
            val plaintext = dek.copyBytes()
            try {
                val sealed = AesGcm.encrypt(
                    key = key,
                    plaintext = plaintext,
                    associatedData = BIP39_WRAP_AAD,
                    random = random,
                )
                Bip39WrappedDek(sealed.nonce, sealed.ciphertext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    override fun unwrap(wrapped: Bip39WrappedDek, password: CharArray): DataEncryptionKey =
        unwrap(wrapped, password, CharArray(0))

    @Throws(GeneralSecurityException::class)
    public fun unwrap(
        wrapped: Bip39WrappedDek,
        recoveryPhrase: CharArray,
        bip39Passphrase: CharArray,
    ): DataEncryptionKey {
        val key = deriveKey(recoveryPhrase, bip39Passphrase)
        return try {
            val plaintext = AesGcm.decrypt(
                key = key,
                nonce = wrapped.nonce,
                ciphertext = wrapped.ciphertext,
                associatedData = BIP39_WRAP_AAD,
            )
            try {
                DataEncryptionKey(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(recoveryPhrase: CharArray, bip39Passphrase: CharArray): ByteArray {
        val phraseCopy = recoveryPhrase.copyOf()
        val passphraseCopy = bip39Passphrase.copyOf()
        val words = try {
            splitWords(phraseCopy)
        } finally {
            phraseCopy.fill('\u0000')
        }
        return try {
            require(validator.isValid(words)) { "Invalid BIP39 recovery phrase or checksum" }
            require(passphraseCopy.all { it.code < 128 }) {
                "The Phase 1 BIP39 passphrase must be ASCII and pre-normalized"
            }

            val mnemonicBytes = utf8Bytes(words)
            val passphraseBytes = utf8Bytes(passphraseCopy)
            val salt = ByteArray(BIP39_SALT_PREFIX.size + passphraseBytes.size)
            BIP39_SALT_PREFIX.copyInto(salt)
            passphraseBytes.copyInto(salt, destinationOffset = BIP39_SALT_PREFIX.size)
            try {
                val seed = pbkdf2HmacSha512(
                    password = mnemonicBytes,
                    salt = salt,
                    iterations = BIP39_PBKDF2_ROUNDS,
                    outputLength = BIP39_SEED_BYTES,
                )
                try {
                    seed.copyOfRange(0, AES_256_KEY_BYTES)
                } finally {
                    seed.fill(0)
                }
            } finally {
                mnemonicBytes.fill(0)
                passphraseBytes.fill(0)
                salt.fill(0)
            }
        } finally {
            words.forEach { it.fill('\u0000') }
            passphraseCopy.fill('\u0000')
        }
    }

    private fun splitWords(phrase: CharArray): Array<CharArray> {
        val result = ArrayList<CharArray>(BIP39_WORD_COUNT_PHRASE)
        var start = -1
        for (index in phrase.indices + phrase.size) {
            val whitespace = index == phrase.size || phrase[index].isWhitespace()
            if (!whitespace && start < 0) start = index
            if (whitespace && start >= 0) {
                result += phrase.copyOfRange(start, index)
                start = -1
            }
        }
        require(result.size == BIP39_WORD_COUNT_PHRASE) {
            "A recovery phrase must contain exactly 24 words"
        }
        return result.toTypedArray()
    }

    private fun utf8Bytes(words: Array<CharArray>): ByteArray {
        val totalLength = words.sumOf { it.size } + words.lastIndex
        val chars = CharArray(totalLength)
        var offset = 0
        words.forEachIndexed { index, word ->
            word.copyInto(chars, destinationOffset = offset)
            offset += word.size
            if (index != words.lastIndex) chars[offset++] = ' '
        }
        return try {
            utf8Bytes(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun utf8Bytes(chars: CharArray): ByteArray {
        val buffer: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(chars))
        val output = ByteArray(buffer.remaining())
        buffer.get(output)
        return output
    }

    private fun pbkdf2HmacSha512(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        outputLength: Int,
    ): ByteArray {
        val output = ByteArray(outputLength)
        var outputOffset = 0
        val blocks = (outputLength + HMAC_SHA512_BYTES - 1) / HMAC_SHA512_BYTES
        for (blockIndex in 1..blocks) {
            val saltAndIndex = ByteArray(salt.size + 4)
            salt.copyInto(saltAndIndex)
            saltAndIndex[salt.size] = (blockIndex ushr 24).toByte()
            saltAndIndex[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltAndIndex[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltAndIndex[salt.size + 3] = blockIndex.toByte()
            var u = hmacSha512(password, saltAndIndex)
            saltAndIndex.fill(0)
            val t = u.copyOf()
            try {
                repeat(iterations - 1) {
                    val next = hmacSha512(password, u)
                    u.fill(0)
                    u = next
                    for (index in t.indices) {
                        t[index] = (t[index].toInt() xor u[index].toInt()).toByte()
                    }
                }
                val bytesToCopy = minOf(t.size, outputLength - outputOffset)
                t.copyInto(output, destinationOffset = outputOffset, endIndex = bytesToCopy)
                outputOffset += bytesToCopy
            } finally {
                u.fill(0)
                t.fill(0)
            }
        }
        return output
    }

    private fun hmacSha512(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(input)
        }
}

/** Generates valid 24-word English BIP39 phrases without a String API. */
public class Bip39RecoveryPhraseGenerator(
    wordList: List<String>,
    private val random: SecureRandom = SecureRandom(),
) {
    private val words = wordList.toTypedArray()

    init {
        require(words.size == BIP39_WORD_COUNT_DICTIONARY) {
            "A BIP39 word list must contain 2048 words"
        }
    }

    public fun generate(): CharArray {
        val entropy = ByteArray(BIP39_ENTROPY_BYTES)
        val digest = ByteArray(SHA256_BYTES)
        val bits = ByteArray(BIP39_ENTROPY_BYTES + 1)
        return try {
            random.nextBytes(entropy)
            entropy.copyInto(bits)
            MessageDigest.getInstance("SHA-256").digest(entropy).copyInto(digest)
            digest.copyInto(bits, destinationOffset = BIP39_ENTROPY_BYTES, endIndex = 1)

            val chosen = IntArray(BIP39_WORD_COUNT_PHRASE)
            for (wordIndex in chosen.indices) {
                var value = 0
                for (bit in 0 until 11) {
                    val position = wordIndex * 11 + bit
                    value = (value shl 1) or ((bits[position / 8].toInt() ushr
                        (7 - (position % 8))) and 1)
                }
                chosen[wordIndex] = value
            }
            val length = chosen.sumOf { words[it].length } + (chosen.size - 1)
            val phrase = CharArray(length)
            var offset = 0
            chosen.forEachIndexed { index, selected ->
                val word = words[selected]
                word.toCharArray().copyInto(phrase, destinationOffset = offset)
                offset += word.length
                if (index != chosen.lastIndex) phrase[offset++] = ' '
            }
            phrase
        } finally {
            entropy.fill(0)
            digest.fill(0)
            bits.fill(0)
        }
    }
}

private const val BIP39_WORD_COUNT_DICTIONARY: Int = 2048
private const val BIP39_WORD_COUNT_PHRASE: Int = 24
private const val BIP39_ENTROPY_BYTES: Int = 32
private const val BIP39_PBKDF2_ROUNDS: Int = 2048
private const val BIP39_SEED_BYTES: Int = 64
private const val HMAC_SHA512_BYTES: Int = 64
private const val SHA256_BYTES: Int = 32
private val BIP39_SALT_PREFIX: ByteArray = "mnemonic".encodeToByteArray()
private val BIP39_WRAP_AAD: ByteArray = "offlinevault|bip39-dek|1".encodeToByteArray()
