package com.example.offlinevault.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Strict UTF-8 conversion without a String at the sensitive-data boundary. */
public object Utf8Codec {
    public fun encode(chars: CharArray): ByteArray {
        val buffer: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(chars))
        val result = ByteArray(buffer.remaining())
        buffer.get(result)
        return result
    }

    public fun decode(bytes: ByteArray): CharArray {
        val buffer: CharBuffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        val result = CharArray(buffer.remaining())
        buffer.get(result)
        return result
    }
}
