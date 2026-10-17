package com.example.offlinevault.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

public class MediaImportController(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val workManager = WorkManager.getInstance(appContext)

    public fun enqueue(uri: Uri): UUID {
        require(uri.scheme == "content") { "Media imports require a content URI" }
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val inputBuilder = Data.Builder()
            .putString(MediaImportWorker.KEY_SOURCE_URI, uri.toString())
        queryDisplayName(uri)?.let {
            inputBuilder.putString(MediaImportWorker.KEY_DISPLAY_NAME, it)
        }
        resolver.getType(uri)?.let {
            inputBuilder.putString(MediaImportWorker.KEY_MIME_TYPE, it)
        }
        queryModifiedAt(uri)?.let {
            inputBuilder.putLong(MediaImportWorker.KEY_MODIFIED_AT, it)
        }
        val input = inputBuilder.build()
        val request = OneTimeWorkRequestBuilder<MediaImportWorker>()
            .setInputData(input)
            .addTag(MediaImportWorker.CHANNEL_ID)
            .build()
        workManager.enqueue(request)
        return request.id
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun queryModifiedAt(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf("last_modified"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}
