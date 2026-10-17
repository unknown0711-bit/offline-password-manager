package com.example.offlinevault.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.service.autofill.FillResponse
import com.example.offlinevault.R

/**
 * Minimal secure handoff activity for unlock and package-integrity decisions.
 *
 * The manifest entry must set `android:excludeFromRecents="true"` and
 * `android:taskAffinity=""`; this Phase 2 scope intentionally does not edit
 * the manifest. All service-created intents also carry
 * [Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS].
 */
public class AutofillAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.autofill_auth_activity)

        val action = intent?.action
        when (action) {
            ACTION_ABORT -> finishWith(Activity.RESULT_CANCELED)
            ACTION_UNLOCK -> authenticateAndReturnFillResponse()
            ACTION_FILL_ANYWAY -> handoffOrReturnFillAnyway()
            else -> finishWith(Activity.RESULT_CANCELED)
        }
    }

    private fun authenticateAndReturnFillResponse() {
        AutofillAuthentication.current().authenticate(this) { response ->
            if (response == null) {
                finishWith(Activity.RESULT_CANCELED)
            } else {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
                )
                finish()
            }
        }
    }

    private fun handoffOrReturnFillAnyway() {
        val response = intent.fillResponseExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT)
        if (response == null) {
            finishWith(Activity.RESULT_CANCELED)
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
            )
            finish()
        }
    }

    private fun finishWith(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    public companion object {
        public const val ACTION_UNLOCK: String = "com.example.offlinevault.autofill.UNLOCK"
        public const val ACTION_ABORT: String = "com.example.offlinevault.autofill.ABORT"
        public const val ACTION_FILL_ANYWAY: String = "com.example.offlinevault.autofill.FILL_ANYWAY"
        public fun createIntentSender(
            context: Context,
            action: String,
            authenticationResult: FillResponse? = null,
        ): IntentSender {
            val intent = Intent(context, AutofillAuthActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            authenticationResult?.let {
                intent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, it)
            }
            return android.app.PendingIntent.getActivity(
                context,
                action.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
        }

    }
}

private fun Intent.fillResponseExtra(key: String): FillResponse? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, FillResponse::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
