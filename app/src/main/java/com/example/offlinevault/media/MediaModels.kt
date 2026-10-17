package com.example.offlinevault.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap

/** Metadata stored inside the encrypted Room EAV payload for a media item. */
public data class MediaMetadata(
    public val displayName: String,
    public val mimeType: String?,
    public val sizeBytes: Long,
    public val modifiedAt: Long?,
    public val sourceUri: String,
    public val sha256: String,
)

public data class MediaRecord(
    public val entryId: String,
    public val fileName: String,
    public val metadata: MediaMetadata,
)

public object MediaFieldKeys {
    public const val METADATA: String = "media_metadata"
}

/**
 * Deterministic, bounded metadata codec. The result is always encrypted with
 * the existing AES-GCM field layer before it reaches Room.
 */
public object MediaMetadataCodec {
    private const val MAGIC: Int = 0x4F564D44 // OVMD
    private const val VERSION: Int = 1
    private const val MAX_PAYLOAD_BYTES: Int = 64 * 1024
    private const val MAX_ENTRIES: Int = 16
    private const val MAX_STRING_BYTES: Int = 16 * 1024
    private const val SHA256_HEX_LENGTH: Int = 64

    public fun encode(metadata: MediaMetadata): ByteArray = encodeMap(
        linkedMapOf(
            "display_name" to metadata.displayName,
            "mime_type" to metadata.mimeType.orEmpty(),
            "size_bytes" to metadata.sizeBytes.toString(),
            "modified_at" to metadata.modifiedAt?.toString().orEmpty(),
            "source_uri" to metadata.sourceUri,
            "sha256" to metadata.sha256,
        ),
    )

    public fun decode(bytes: ByteArray): MediaMetadata {
        val values = decodeMap(bytes)
        val digest = values.required("sha256")
        require(digest.length == SHA256_HEX_LENGTH && digest.all(Char::isHexDigit)) {
            "Invalid media SHA-256"
        }
        return MediaMetadata(
            displayName = values.required("display_name"),
            mimeType = values["mime_type"]?.takeIf(String::isNotEmpty),
            sizeBytes = values.required("size_bytes").toNonNegativeLong("size_bytes"),
            modifiedAt = values["modified_at"]?.takeIf(String::isNotEmpty)
                ?.toNonNegativeLong("modified_at"),
            sourceUri = values.required("source_uri"),
            sha256 = digest,
        )
    }

    public fun encodeMap(values: Map<String, String>): ByteArray {
        require(values.size <= MAX_ENTRIES) { "Too many metadata fields" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(values.size)
            values.toSortedMap().forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 128 && key.all { it.code in 0x20..0x7E }) {
                    "Invalid metadata key"
                }
                writeString(data, key)
                writeString(data, value)
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Media metadata is too large" }
        }
    }

    public fun decodeMap(bytes: ByteArray): Map<String, String> {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Media metadata is too large" }
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Unknown media metadata" }
            require(data.readInt() == VERSION) { "Unsupported media metadata version" }
            val count = data.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid media metadata field count" }
            val values = LinkedHashMap<String, String>(count)
            repeat(count) {
                val key = readString(data)
                val value = readString(data)
                require(values.put(key, value) == null) { "Duplicate media metadata field" }
            }
            require(data.available() == 0) { "Trailing media metadata" }
            return values
        }
    }

    private fun writeString(data: DataOutputStream, value: String) {
        val encoded = value.encodeToByteArray()
        try {
            require(encoded.size <= MAX_STRING_BYTES) { "Media metadata value is too large" }
            data.writeInt(encoded.size)
            data.write(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun readString(data: DataInputStream): String {
        val size = data.readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid media metadata string length" }
        val encoded = ByteArray(size)
        return try {
            data.readFully(encoded)
            String(encoded, Charsets.UTF_8)
        } finally {
            encoded.fill(0)
        }
    }

    private fun Map<String, String>.required(key: String): String = get(key)
        ?: error("Missing media metadata field: $key")

    private fun String.toNonNegativeLong(field: String): Long = toLongOrNull()
        ?.takeIf { it >= 0L }
        ?: error("Invalid media metadata field: $field")
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'
