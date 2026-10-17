package com.example.offlinevault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.example.offlinevault.R

/** Builds platform Autofill responses and keeps all RemoteViews details in one place. */
public object AutofillPresentation {
    public fun lockedResponse(
        context: Context,
        request: ParsedAutofillRequest,
        authentication: IntentSender,
    ): FillResponse = FillResponse.Builder()
        .setAuthentication(
            request.fieldIds.all.toTypedArray(),
            authentication,
            lockedRow(context),
        )
        .build()

    public fun credentialResponse(
        context: Context,
        request: ParsedAutofillRequest,
        credentials: List<AutofillCredential>,
    ): FillResponse? {
        val response = FillResponse.Builder()
        var count = 0
        credentials.forEach { credential ->
            val password = credential.passwordCopy()
            try {
                val dataset = Dataset.Builder(credentialRow(context, credential))
                request.fieldIds.username.forEach { id ->
                    dataset.setValue(id, AutofillValue.forText(credential.username))
                }
                request.fieldIds.password.forEach { id ->
                    dataset.setValue(id, AutofillValue.forText(String(password)))
                }
                if (request.fieldIds.username.isNotEmpty() || request.fieldIds.password.isNotEmpty()) {
                    response.addDataset(dataset.build())
                    count++
                }
            } finally {
                password.fill('\u0000')
            }
        }
        return if (count == 0) null else response.build()
    }

    public fun mismatchWarningResponse(
        context: Context,
        request: ParsedAutofillRequest,
        abort: IntentSender,
        fillAnyway: IntentSender,
        fillAnywayResponse: FillResponse? = null,
    ): FillResponse = FillResponse.Builder()
        // Selecting the row itself is the explicit fill-anyway action. The two
        // buttons in the RemoteViews provide the same choices where supported.
        .setAuthentication(
            request.fieldIds.all.toTypedArray(),
            fillAnyway,
            warningRow(context, abort, fillAnyway, fillAnywayResponse),
        )
        .build()

    public fun lockedRow(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_locked_item).apply {
            setTextViewText(R.id.autofill_locked_message, context.getString(R.string.autofill_locked_message))
        }

    public fun credentialRow(context: Context, credential: AutofillCredential): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_credential_item).apply {
            setTextViewText(R.id.autofill_credential_title, credential.title)
            setTextViewText(R.id.autofill_credential_username, maskUsername(credential.username))
        }

    public fun warningRow(
        context: Context,
        abort: IntentSender,
        fillAnyway: IntentSender,
        fillAnywayResponse: FillResponse? = null,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.autofill_warning_item).apply {
        setTextViewText(R.id.autofill_warning_message, context.getString(R.string.autofill_warning_message))
        setOnClickPendingIntent(R.id.autofill_warning_abort, actionPendingIntent(context, ACTION_ABORT))
        setOnClickPendingIntent(
            R.id.autofill_warning_fill_anyway,
            actionPendingIntent(context, ACTION_FILL_ANYWAY, fillAnywayResponse),
        )
    }

    /** Keeps a useful prefix and email domain while avoiding a full username disclosure. */
    public fun maskUsername(username: String): String {
        if (username.isEmpty()) return "•••"
        val at = username.indexOf('@')
        if (at > 0 && at < username.lastIndex) {
            val local = username.substring(0, at)
            val domain = username.substring(at + 1)
            return maskLocal(local) + "@" + domain
        }
        return maskLocal(username)
    }

    private fun maskLocal(value: String): String = when {
        value.length == 1 -> "*"
        value.length == 2 -> value.first() + "*"
        else -> value.take(minOf(5, value.length)) + "***"
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        authenticationResult: FillResponse? = null,
    ): PendingIntent {
        val intent = Intent(context, AutofillAuthActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        authenticationResult?.let {
            intent.putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, it)
        }
        return PendingIntent.getActivity(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val ACTION_ABORT = "com.example.offlinevault.autofill.ABORT"
    private const val ACTION_FILL_ANYWAY = "com.example.offlinevault.autofill.FILL_ANYWAY"
}
