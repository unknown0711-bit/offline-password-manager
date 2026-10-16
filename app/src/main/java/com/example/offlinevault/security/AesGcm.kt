package com.example.offlinevault.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class AesGcmSealed(
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    val nonce: ByteArray = nonce.copyOf()
    val ciphertext: ByteArray = ciphertext.copyOf()
}

internal object AesGcm {
    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
        random: SecureRandom,
    ): AesGcmSealed {
        checkKey(key)
        val nonce = ByteArray(AES_GCM_NONCE_BYTES)
        random.nextBytes(nonce)
        return try {
            AesGcmSealed(
                nonce = nonce,
                ciphertext = crypt(
                    mode = Cipher.ENCRYPT_MODE,
                    key = key,
                    nonce = nonce,
                    input = plaintext,
                    associatedData = associatedData,
                ),
            )
        } finally {
            nonce.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        checkKey(key)
        require(nonce.size == AES_GCM_NONCE_BYTES) { "AES-GCM requires a 12-byte nonce" }
        return crypt(
            mode = Cipher.DECRYPT_MODE,
            key = key,
            nonce = nonce,
            input = ciphertext,
            associatedData = associatedData,
        )
    }

    private fun crypt(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            mode,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AES_GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(input)
    }

    private fun checkKey(key: ByteArray) {
        require(key.size == AES_256_KEY_BYTES) { "AES-GCM requires a 32-byte key" }
    }
}
