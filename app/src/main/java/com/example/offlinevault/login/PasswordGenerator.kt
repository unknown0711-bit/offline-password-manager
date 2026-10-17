package com.example.offlinevault.login

import java.security.SecureRandom

public data class PasswordGeneratorOptions(
    public val length: Int = 20,
    public val includeUppercase: Boolean = true,
    public val includeLowercase: Boolean = true,
    public val includeDigits: Boolean = true,
    public val includeSymbols: Boolean = true,
) {
    init {
        require(length in 8..128) { "Password length must be between 8 and 128" }
        require(includeUppercase || includeLowercase || includeDigits || includeSymbols) {
            "At least one character class must be selected"
        }
    }
}

public class PasswordGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    public fun generate(options: PasswordGeneratorOptions = PasswordGeneratorOptions()): CharArray {
        val pools = buildList {
            if (options.includeUppercase) add(UPPERCASE)
            if (options.includeLowercase) add(LOWERCASE)
            if (options.includeDigits) add(DIGITS)
            if (options.includeSymbols) add(SYMBOLS)
        }
        val selected = CharArray(options.length)
        val all = pools.joinToString(separator = "")
        try {
            pools.forEachIndexed { index, pool -> selected[index] = pool[random.nextInt(pool.length)] }
            for (index in pools.size until selected.size) selected[index] = all[random.nextInt(all.length)]
            shuffle(selected)
            return selected
        } catch (error: Throwable) {
            selected.fill('\u0000')
            throw error
        }
    }

    private fun shuffle(chars: CharArray) {
        for (index in chars.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val value = chars[index]
            chars[index] = chars[other]
            chars[other] = value
        }
    }

    private companion object {
        const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val LOWERCASE = "abcdefghijkmnopqrstuvwxyz"
        const val DIGITS = "23456789"
        const val SYMBOLS = "!@#%^&*()-_=+[]{}:,.?"
    }
}
