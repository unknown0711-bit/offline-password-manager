package com.example.offlinevault.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Offline Autofill service. It never performs network or browser I/O. */
public class VaultAutofillService : AutofillService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val requestParser = AutofillRequestParser()

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val parsed = requestParser.parse(request)
        if (parsed == null) {
            callback.onSuccess(null)
            return
        }
        val appContext = applicationContext
        val work = serviceScope.launch {
            try {
                val dependencies = AutofillDependencies.current(appContext)
                val trust = parsed.clientPackageName
                    ?.let { dependencies.clientTrust(appContext).verify(it) }
                    ?: AutofillClientTrust.Mismatch("The client package name is unavailable")
                val lookup = dependencies.vaultStore(appContext).lookup(parsed.webDomains)
                if (trust is AutofillClientTrust.Mismatch) {
                    try {
                        val fillAnywayResponse = (lookup as? VaultAutofillLookup.Unlocked)?.let {
                            AutofillPresentation.credentialResponse(appContext, parsed, it.credentials)
                        }
                        callback.onSuccess(
                            AutofillPresentation.mismatchWarningResponse(
                                context = appContext,
                                request = parsed,
                                abort = AutofillAuthActivity.createIntentSender(
                                    appContext,
                                    AutofillAuthActivity.ACTION_ABORT,
                                ),
                                fillAnyway = AutofillAuthActivity.createIntentSender(
                                    appContext,
                                    AutofillAuthActivity.ACTION_FILL_ANYWAY,
                                    fillAnywayResponse,
                                ),
                                fillAnywayResponse = fillAnywayResponse,
                            ),
                        )
                    } finally {
                        lookup.close()
                    }
                    return@launch
                }

                when (lookup) {
                    VaultAutofillLookup.Locked -> callback.onSuccess(
                        AutofillPresentation.lockedResponse(
                            context = appContext,
                            request = parsed,
                            authentication = dependencies.authenticationIntentSender(appContext, parsed),
                        ),
                    )

                    is VaultAutofillLookup.Unlocked -> {
                        try {
                            callback.onSuccess(
                                AutofillPresentation.credentialResponse(
                                    context = appContext,
                                    request = parsed,
                                    credentials = lookup.credentials,
                                ),
                            )
                        } finally {
                            lookup.close()
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation is expected when the client abandons the request.
            } catch (error: Throwable) {
                callback.onFailure(error.message ?: "Autofill is unavailable")
            }
        }
        cancellationSignal.setOnCancelListener { work.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: android.service.autofill.SaveCallback) {
        // Phase 2 is fill-only. No credentials are persisted from a client form.
        callback.onSuccess()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
