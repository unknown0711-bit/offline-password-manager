package com.example.offlinevault.security

/** Derives purpose-bound SQLCipher material from the unlocked DEK via HKDF. */
public class SqlCipherKeyDeriver {
    public fun derive(dek: DataEncryptionKey): ByteArray {
        val input = dek.copyBytes()
        return try {
            HkdfSha256.deriveKey(
                ikm = input,
                salt = SQLCIPHER_HKDF_SALT,
                info = SQLCIPHER_HKDF_INFO,
                outputLengthBytes = AES_256_KEY_BYTES,
            )
        } finally {
            input.fill(0)
        }
    }

    private companion object {
        val SQLCIPHER_HKDF_SALT: ByteArray = "offlinevault/sqlcipher/salt/v1".encodeToByteArray()
        val SQLCIPHER_HKDF_INFO: ByteArray = "offlinevault/sqlcipher/passphrase/v1".encodeToByteArray()
    }
}
