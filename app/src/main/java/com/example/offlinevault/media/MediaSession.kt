package com.example.offlinevault.media

/** Process-local unlocked media seam; it is cleared before the DEK is wiped. */
public object MediaSession {
    @Volatile private var repository: MediaRepository? = null

    @Synchronized
    public fun install(repository: MediaRepository) {
        this.repository?.close()
        this.repository = repository
    }

    public fun current(): MediaRepository? = repository

    public fun requireCurrent(): MediaRepository = current()
        ?: error("The media session is locked")

    @Synchronized
    public fun clear() {
        val current = repository
        repository = null
        current?.close()
        MediaThumbnailCacheRegistry.clear()
        PdfTempFileRegistry.clear()
    }
}
