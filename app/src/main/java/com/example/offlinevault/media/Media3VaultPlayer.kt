package com.example.offlinevault.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Creates an ExoPlayer that reads only through the encrypted seekable source. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
public class Media3VaultPlayerFactory(
    context: Context,
    private val repository: MediaRepository,
) {
    private val appContext = context.applicationContext

    public fun create(record: MediaRecord): ExoPlayer {
        val dataSourceFactory = Media3VaultDataSource.Factory(repository)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                val mime = record.metadata.mimeType
                setMediaItem(
                    MediaItem.Builder()
                        .setUri("vault://media/${record.entryId}")
                        .setMimeType(mime ?: "application/octet-stream")
                        .build(),
                )
                prepare()
            }
    }
}
