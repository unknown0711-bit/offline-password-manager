package com.example.offlinevault.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Copies a secret, marks it sensitive, and schedules a durable 30-second clear. */
public class SensitiveClipboard(context: Context) {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)

    init {
        SensitiveClipboardRegistry.install(this)
    }

    public fun copy(value: CharArray, label: String = CLIP_LABEL) {
        val text = value.concatToString()
        try {
            val clip = ClipData.newPlainText(label, text)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras?.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboardManager.setPrimaryClip(clip)
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                CLEAR_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                    .setInitialDelay(CLEAR_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                    .build(),
            )
        } finally {
            value.fill('\u0000')
        }
    }

    /** Call this from the vault session lock transition. */
    public fun clearNow() {
        clear(clipboardManager)
        WorkManager.getInstance(appContext).cancelUniqueWork(CLEAR_WORK_NAME)
    }

    public companion object {
        public const val CLIP_LABEL: String = "Offline Vault"
        public const val CLEAR_DELAY_MILLIS: Long = 30_000L
        private const val CLEAR_WORK_NAME: String = "offline-vault-clear-sensitive-clipboard"

        internal fun clear(manager: ClipboardManager) {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, ""))
        }
    }
}

/** Process-local lock hook; the session coordinator calls it before dropping the DEK. */
public object SensitiveClipboardRegistry {
    @Volatile
    private var current: SensitiveClipboard? = null

    internal fun install(clipboard: SensitiveClipboard) {
        current = clipboard
    }

    public fun clearOnVaultLock() {
        current?.clearNow()
        current = null
    }
}
