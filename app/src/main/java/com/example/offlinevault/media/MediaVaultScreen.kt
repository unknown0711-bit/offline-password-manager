package com.example.offlinevault.media

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.offlinevault.ui.OfflineVaultTheme
import com.example.offlinevault.ui.TerminalButton
import com.example.offlinevault.ui.TerminalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
public fun MediaVaultScreen(
    repository: MediaRepository,
    onBack: () -> Unit,
    sharedUri: android.net.Uri? = null,
    onSharedImportHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val records by repository.observeMedia().collectAsState(emptyList())
    val importController = remember(context) { MediaImportController(context) }
    var importWorkId by remember { mutableStateOf<UUID?>(null) }
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importWorkId = importController.enqueue(uri)
    }
    val thumbnails = remember { MediaThumbnailCache() }
    var selected by remember { mutableStateOf<MediaRecord?>(null) }
    var deleteSource by remember { mutableStateOf<android.net.Uri?>(null) }
    var handledSharedUri by remember { mutableStateOf<String?>(null) }
    var workInfo by remember { mutableStateOf<WorkInfo?>(null) }

    DisposableEffect(Unit) {
        MediaThumbnailCacheRegistry.install(thumbnails)
        onDispose {
            thumbnails.clear()
            MediaThumbnailCacheRegistry.clear()
        }
    }
    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        if (handledSharedUri != uri.toString()) {
            importWorkId = importController.enqueue(uri)
            handledSharedUri = uri.toString()
            onSharedImportHandled()
        }
    }
    LaunchedEffect(importWorkId) {
        val id = importWorkId ?: return@LaunchedEffect
        WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { workInfo = it }
    }
    LaunchedEffect(workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state == WorkInfo.State.SUCCEEDED) {
            deleteSource = info.outputData.getString(MediaImportWorker.KEY_SOURCE_URI)
                ?.let(android.net.Uri::parse)
            importWorkId = null
        }
    }

    OfflineVaultTheme {
        if (selected != null) {
            MediaPreviewScreen(
                record = selected!!,
                repository = repository,
                thumbnails = thumbnails,
                onBack = { selected = null },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TerminalButton("Back", onBack)
                Text("Media vault", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Encrypted locally. Original files are never deleted automatically.",
                    color = MaterialTheme.colorScheme.secondary,
                )
                TerminalButton(
                    "Import from device",
                    onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                )
                ImportProgress(workInfo)
                if (records.isEmpty()) {
                    Text("No media yet.", color = MaterialTheme.colorScheme.secondary)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(records, key = { it.entryId }) { record ->
                            MediaGridItem(record, repository, thumbnails) { selected = record }
                        }
                    }
                }
            }
        }
    }

    deleteSource?.let { uri ->
        DeleteOriginalDialog(
            uri = uri,
            onDismiss = { deleteSource = null },
        )
    }
}

@Composable
private fun ImportProgress(workInfo: WorkInfo?) {
    when (workInfo?.state) {
        WorkInfo.State.RUNNING -> {
            val copied = workInfo.progress.getLong(MediaImportWorker.KEY_BYTES_COPIED, 0L)
            val total = workInfo.progress.getLong(MediaImportWorker.KEY_TOTAL_BYTES, -1L)
            val fraction = workInfo.progress.getFloat(MediaImportWorker.KEY_FRACTION, -1f)
            TerminalCard {
                Text("Importing media", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (total > 0L) "$copied / $total bytes" else "$copied bytes",
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (fraction in 0f..1f) CircularProgressIndicator(progress = { fraction })
            }
        }
        WorkInfo.State.FAILED -> Text(
            workInfo.outputData.getString(MediaImportWorker.KEY_ERROR) ?: "Import failed",
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }
}

@Composable
private fun MediaGridItem(
    record: MediaRecord,
    repository: MediaRepository,
    thumbnails: MediaThumbnailCache,
    onClick: () -> Unit,
) {
    var bitmap by remember(record.entryId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.entryId) {
        bitmap = thumbnails.getOrLoad(record, repository)
    }
    TerminalCard {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = record.metadata.displayName,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mediaKindLabel(record.metadata.mimeType))
                }
            }
            Text(record.metadata.displayName, maxLines = 1)
            Text(
                "${record.metadata.sizeBytes} bytes · ${record.metadata.mimeType ?: "unknown"}",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MediaPreviewScreen(
    record: MediaRecord,
    repository: MediaRepository,
    thumbnails: MediaThumbnailCache,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalButton("Back", onBack)
        Text(record.metadata.displayName, style = MaterialTheme.typography.titleLarge)
        when {
            record.metadata.mimeType?.startsWith("image/") == true -> {
                var bitmap by remember(record.entryId) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(record.entryId) {
                    bitmap = thumbnails.getOrLoad(record, repository)
                }
                bitmap?.let {
                    Image(it.asImageBitmap(), record.metadata.displayName, modifier = Modifier.fillMaxWidth())
                }
            }
            record.metadata.mimeType == "application/pdf" -> PdfViewer(record, repository, context)
            record.metadata.mimeType?.startsWith("video/") == true ||
                record.metadata.mimeType?.startsWith("audio/") == true ->
                MediaPlayer(record, repository, context)
            else -> Text("No viewer is available for this MIME type.")
        }
    }
}

@Composable
private fun MediaPlayer(record: MediaRecord, repository: MediaRepository, context: android.content.Context) {
    val player = remember(record.entryId) { Media3VaultPlayerFactory(context, repository).create(record) }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(320.dp),
    )
}

@Composable
private fun PdfViewer(record: MediaRecord, repository: MediaRepository, context: android.content.Context) {
    val manager = remember(context) { PdfTempFileManager(context) }
    var file by remember(record.entryId) { mutableStateOf<File?>(null) }
    LaunchedEffect(record.entryId) {
        file = withContext(Dispatchers.IO) { manager.decryptToTemp(repository, record.entryId) }
    }
    DisposableEffect(file) {
        onDispose { file?.let(manager::delete) }
    }
    if (file == null) CircularProgressIndicator()
    else AndroidView(
        factory = { PdfPageView(it) },
        update = { it.setFile(file!!) },
        modifier = Modifier.fillMaxWidth().height(520.dp),
    )
}

@Composable
private fun DeleteOriginalDialog(uri: android.net.Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val request = remember(uri) { SourceDeleteRequests.create(context, uri) }
    val senderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete original file?") },
        text = {
            Text("DELETES VIA SYSTEM. FLASH STORAGE MAY RETAIN FRAGMENTS.")
        },
        confirmButton = {
            TextButton(onClick = {
                when (request) {
                    is SourceDeleteRequest.MediaStoreRequest -> senderLauncher.launch(
                        IntentSenderRequest.Builder(request.intentSender).build(),
                    )
                    is SourceDeleteRequest.SafRequest -> {
                        SourceDeleteRequests.deleteSaf(context, request)
                        onDismiss()
                    }
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

private fun mediaKindLabel(mimeType: String?): String = when {
    mimeType?.startsWith("image/") == true -> "IMAGE"
    mimeType?.startsWith("video/") == true -> "VIDEO"
    mimeType?.startsWith("audio/") == true -> "AUDIO"
    mimeType == "application/pdf" -> "PDF"
    else -> "FILE"
}

private class PdfPageView(context: android.content.Context) : View(context) {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var page: PdfRenderer.Page? = null
    private var bitmap: Bitmap? = null
    private var currentFile: File? = null

    fun setFile(file: File) {
        if (currentFile == file) return
        closePdf()
        currentFile = file
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor!!)
        page = renderer!!.openPage(0)
        bitmap = Bitmap.createBitmap(page!!.width, page!!.height, Bitmap.Config.ARGB_8888)
        page!!.render(bitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        bitmap?.let { canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), Paint()) }
    }

    override fun onDetachedFromWindow() {
        closePdf()
        super.onDetachedFromWindow()
    }

    private fun closePdf() {
        page?.close()
        renderer?.close()
        descriptor?.close()
        bitmap?.recycle()
        page = null
        renderer = null
        descriptor = null
        bitmap = null
        currentFile = null
    }
}
