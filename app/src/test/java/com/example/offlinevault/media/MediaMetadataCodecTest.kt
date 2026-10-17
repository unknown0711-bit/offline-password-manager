package com.example.offlinevault.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaMetadataCodecTest {
    private val metadata = MediaMetadata(
        displayName = "holiday.mp4",
        mimeType = "video/mp4",
        sizeBytes = 123_456L,
        modifiedAt = 1_700_000_000_000L,
        sourceUri = "content://media/external/video/media/7",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    @Test
    fun roundTripsDeterministically() {
        val first = MediaMetadataCodec.encode(metadata)
        val second = MediaMetadataCodec.encode(metadata)
        assertContentEquals(first, second)
        assertEquals(metadata, MediaMetadataCodec.decode(first))
    }

    @Test
    fun rejectsTrailingBytes() {
        val encoded = MediaMetadataCodec.encode(metadata)
        assertFailsWith<IllegalArgumentException> {
            MediaMetadataCodec.decode(encoded + byteArrayOf(1))
        }
    }
}
