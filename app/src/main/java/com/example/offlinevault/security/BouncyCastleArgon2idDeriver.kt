package com.example.offlinevault.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * The Android/JCA providers do not expose Argon2id. Bouncy Castle's
 * Argon2BytesGenerator is a self-contained, audited implementation that keeps
 * this project independent from a native library.
 */
public class BouncyCastleArgon2idDeriver : Argon2idDeriver {
    override fun derive(
        password: CharArray,
        salt: ByteArray,
        parameters: Argon2idParameters,
        outputLengthBytes: Int,
    ): ByteArray {
        require(outputLengthBytes > 0) { "Argon2id output length must be positive" }
        val saltCopy = salt.copyOf()
        val passwordCopy = password.copyOf()
        val output = ByteArray(outputLengthBytes)
        return try {
            val argonParameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(saltCopy)
                .withMemoryAsKB(parameters.memoryKiB)
                .withIterations(parameters.iterations)
                .withParallelism(parameters.parallelism)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(argonParameters)
            generator.generateBytes(passwordCopy, output)
            output
        } finally {
            saltCopy.fill(0)
            passwordCopy.fill('\u0000')
        }
    }
}
