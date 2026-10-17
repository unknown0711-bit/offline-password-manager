package com.example.offlinevault.media

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

public sealed interface SourceDeleteRequest {
    public val uri: Uri

    public data class MediaStoreRequest(
        override val uri: Uri,
        public val intentSender: IntentSender,
    ) : SourceDeleteRequest

    public data class SafRequest(override val uri: Uri) : SourceDeleteRequest
}

/** Deletion is always explicit and user-confirmed; imports never delete sources. */
public object SourceDeleteRequests {
    public fun create(context: Context, uri: Uri): SourceDeleteRequest {
        require(uri.scheme == "content") { "Source deletion requires a content URI" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
            val request = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            }.getOrNull()
            if (request != null) {
                return SourceDeleteRequest.MediaStoreRequest(uri, request.intentSender)
            }
        }
        return SourceDeleteRequest.SafRequest(uri)
    }

    public fun deleteSaf(context: Context, request: SourceDeleteRequest.SafRequest): Boolean =
        runCatching {
            if (DocumentsContract.isDocumentUri(context, request.uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, request.uri)
            } else {
                context.contentResolver.delete(request.uri, null, null) > 0
            }
        }.getOrDefault(false)
}
