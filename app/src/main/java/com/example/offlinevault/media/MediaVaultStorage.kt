package com.example.offlinevault.media

import android.content.Context
import java.io.File

public class MediaVaultStorage(rootDirectory: File) {
    public val vaultDirectory: File = rootDirectory
    private val policy = MediaPathPolicy(rootDirectory.toPath())

    public constructor(context: Context) : this(File(context.applicationContext.filesDir, "vault"))

    public fun ensureDirectory() {
        require(vaultDirectory.isDirectory || vaultDirectory.mkdirs()) {
            "Unable to create the private media vault directory"
        }
    }

    public fun newFileName(): String = policy.newFileName()

    public fun fileFor(fileName: String): File {
        ensureDirectory()
        return policy.resolve(fileName).toFile()
    }

    public fun validate(file: File): File = policy.validateExisting(file.toPath()).toFile()
}
