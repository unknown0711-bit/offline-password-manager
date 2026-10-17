package com.example.offlinevault.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/** Foreground, offline import worker for one SAF or share content URI. */
public class MediaImportWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val rawUri = inputData.getString(KEY_SOURCE_URI) ?: return failure("Missing source URI")
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            ?: return failure("Invalid source URI")
        if (uri.scheme != "content") return failure("Only content URIs are accepted")
        val repository = MediaSession.current() ?: return failure("The vault is locked")

        setForeground(createForegroundInfo(applicationContext))
        var lastReportedBytes = -PROGRESS_STEP_BYTES
        return try {
            val record = repository.importUri(
                uri = uri,
                displayName = inputData.getString(KEY_DISPLAY_NAME),
                mimeType = inputData.getString(KEY_MIME_TYPE),
                modifiedAt = inputData.getLong(KEY_MODIFIED_AT, Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE },
                onProgress = { progress ->
                    if (progress.copiedBytes - lastReportedBytes >= PROGRESS_STEP_BYTES ||
                        progress.fraction == 1f
                    ) {
                        lastReportedBytes = progress.copiedBytes
                        setProgress(
                            workDataOf(
                                KEY_BYTES_COPIED to progress.copiedBytes,
                                KEY_TOTAL_BYTES to (progress.totalBytes ?: -1L),
                                KEY_FRACTION to (progress.fraction ?: -1f),
                            ),
                        )
                    }
                },
            )
            Result.success(
                workDataOf(
                    KEY_MEDIA_ID to record.entryId,
                    KEY_SOURCE_URI to rawUri,
                    KEY_DISPLAY_NAME to record.metadata.displayName,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "Media import failed")
        }
    }

    private fun createForegroundInfo(context: Context): ForegroundInfo {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media imports", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return ForegroundInfo(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Importing media")
                .setContentText("Encrypting into your vault")
                .setOngoing(true)
                .build(),
        )
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(KEY_ERROR to message.take(MAX_ERROR_LENGTH)),
    )

    public companion object {
        public const val KEY_SOURCE_URI: String = "source_uri"
        public const val KEY_DISPLAY_NAME: String = "display_name"
        public const val KEY_MIME_TYPE: String = "mime_type"
        public const val KEY_MODIFIED_AT: String = "modified_at"
        public const val KEY_MEDIA_ID: String = "media_id"
        public const val KEY_BYTES_COPIED: String = "bytes_copied"
        public const val KEY_TOTAL_BYTES: String = "total_bytes"
        public const val KEY_FRACTION: String = "fraction"
        public const val KEY_ERROR: String = "error"
        public const val CHANNEL_ID: String = "media_imports"
        public const val NOTIFICATION_ID: Int = 0x4D56
        private const val PROGRESS_STEP_BYTES: Long = 256 * 1024
        private const val MAX_ERROR_LENGTH: Int = 256
    }
}
