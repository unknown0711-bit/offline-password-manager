package com.example.offlinevault.media

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Owns short-lived PDF files under noBackupFilesDir/tmp. Every created file is
 * registered so the session lock can delete it even if the viewer is alive.
 */
public class PdfTempFileManager(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "tmp")

    public fun decryptToTemp(repository: MediaRepository, entryId: String): File {
        require(root.isDirectory || root.mkdirs()) { "Unable to create PDF temp directory" }
        val file = File(root, "${UUID.randomUUID()}.pdf")
        repository.decryptToFile(entryId, file)
        PdfTempFileRegistry.register(file)
        return file
    }

    public fun delete(file: File) {
        PdfTempFileRegistry.unregister(file)
        file.delete()
    }
}

public object PdfTempFileRegistry {
    private val files = linkedSetOf<File>()

    @Synchronized
    public fun register(file: File) {
        files += file
    }

    @Synchronized
    public fun unregister(file: File) {
        files -= file
    }

    @Synchronized
    public fun clear() {
        files.forEach(File::delete)
        files.clear()
    }
}
