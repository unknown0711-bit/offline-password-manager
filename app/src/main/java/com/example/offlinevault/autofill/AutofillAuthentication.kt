package com.example.offlinevault.autofill

import android.app.Activity
import android.service.autofill.FillResponse

/**
 * Process-local handoff for the real vault unlock flow. The app installs this
 * callback after its authentication/session coordinator is initialized. The
 * fail-closed default never exposes credentials from an autofill request.
 */
public fun interface AutofillAuthenticationHandler {
    public fun authenticate(activity: Activity, callback: (FillResponse?) -> Unit)
}

public object AutofillAuthentication {
    @Volatile
    private var handler: AutofillAuthenticationHandler = AutofillAuthenticationHandler { _, callback ->
        callback(null)
    }

    public fun install(handler: AutofillAuthenticationHandler) {
        this.handler = handler
    }

    public fun reset() {
        handler = AutofillAuthenticationHandler { _, callback -> callback(null) }
    }

    internal fun current(): AutofillAuthenticationHandler = handler
}
