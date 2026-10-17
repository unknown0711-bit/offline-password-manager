package com.example.offlinevault.ui

import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.text.TextUtils
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Clears only the sensitive clip owned by Offline Vault, not a later user copy. */
public class ClipboardClearWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val manager = applicationContext.getSystemService(android.content.ClipboardManager::class.java)
        val description = manager.primaryClipDescription ?: return Result.success()
        val isSensitive = Build.VERSION.SDK_INT >= 33 &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        val label = description.label?.toString()
        if (isSensitive && TextUtils.equals(label, SensitiveClipboard.CLIP_LABEL)) {
            SensitiveClipboard.clear(manager)
        }
        return Result.success()
    }
}
