package com.example.offlinevault.security

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HkdfSha256Test {
    @Test
    fun matchesRfc5869TestVectorOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c,
        )
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val expected = "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"

        val actual = HkdfSha256.deriveKey(ikm, salt, info, 42)
        assertContentEquals(expected.hexToBytes(), actual)
        ikm.fill(0)
        salt.fill(0)
        info.fill(0)
        actual.fill(0)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
