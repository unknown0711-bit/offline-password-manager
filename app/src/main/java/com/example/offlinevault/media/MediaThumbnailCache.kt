package com.example.offlinevault.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory-only thumbnail cache. It never writes an image to disk. */
public class MediaThumbnailCache(
    maxKilobytes: Int = (Runtime.getRuntime().maxMemory() / 1024 / 8)
        .toInt()
        .coerceIn(4 * 1024, 32 * 1024),
) {
    private val cache = object : LruCache<String, Bitmap>(maxKilobytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    public suspend fun getOrLoad(
        record: MediaRecord,
        repository: MediaRepository,
    ): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(record.entryId)?.let { return@withContext it }
        if (record.metadata.mimeType?.startsWith("image/") != true) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        repository.openDecrypted(record.entryId).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val sample = calculateSample(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = repository.openDecrypted(record.entryId).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@withContext null
        cache.put(record.entryId, bitmap)
        bitmap
    }

    public fun clear() = cache.evictAll()

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_DIMENSION: Int = 512
    }
}

public object MediaThumbnailCacheRegistry {
    @Volatile private var current: MediaThumbnailCache? = null

    public fun install(cache: MediaThumbnailCache) {
        current?.clear()
        current = cache
    }

    public fun clear() {
        current?.clear()
        current = null
    }
}
