package com.example.offlinevault.media

import java.nio.file.Path
import java.util.UUID

/** Pure path policy: ciphertext names are canonical UUIDs with no extension. */
public class MediaPathPolicy(vaultDirectory: Path) {
    public val vaultDirectory: Path = vaultDirectory.toAbsolutePath().normalize()

    public fun newFileName(): String = UUID.randomUUID().toString()

    public fun resolve(fileName: String): Path {
        require(isUuidOnlyFileName(fileName)) { "Media filename must be a UUID" }
        val resolved = vaultDirectory.resolve(fileName).normalize()
        require(resolved.parent == vaultDirectory) { "Media path escapes vault directory" }
        return resolved
    }

    public fun validateExisting(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == vaultDirectory) { "Media path is outside vault directory" }
        require(isUuidOnlyFileName(normalized.fileName.toString())) {
            "Media filename must be a UUID"
        }
        return normalized
    }

    public companion object {
        private val UUID_FILE_NAME = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )

        public fun isUuidOnlyFileName(fileName: String): Boolean = UUID_FILE_NAME.matches(fileName)
    }
}
