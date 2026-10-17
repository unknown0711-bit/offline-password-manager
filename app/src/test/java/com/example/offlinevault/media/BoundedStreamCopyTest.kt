package com.example.offlinevault.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedStreamCopyTest {
    @Test
    fun copiesWithBoundedBufferAndReportsProgress() {
        val source = ByteArray(40_000) { (it and 0xFF).toByte() }
        val progress = mutableListOf<CopyProgress>()
        val output = ByteArrayOutputStream()
        val result = BoundedStreamCopy.copy(
            input = ByteArrayInputStream(source),
            output = output,
            totalBytes = source.size.toLong(),
            bufferSize = 1024,
            onProgress = progress::add,
        )
        assertEquals(source.size.toLong(), result.copiedBytes)
        assertContentEquals(source, output.toByteArray())
        assertEquals(1f, progress.last().fraction)
        assertFailsWith<IllegalArgumentException> {
            BoundedStreamCopy.copy(
                ByteArrayInputStream(source),
                ByteArrayOutputStream(),
                bufferSize = 65 * 1024,
            )
        }
    }
}
