package com.example.offlinevault.autofill

import kotlin.test.Test
import kotlin.test.assertIs

class AutofillClientIntegrityTest {
    @Test
    fun pinnedPolicyRejectsPackageAndSignatureMismatch() {
        val policy = PinnedAutofillClientTrustPolicy(
            mapOf("com.example.browser" to setOf("ABC123")),
        )
        assertIs<AutofillClientTrust.Mismatch>(
            policy.evaluate(AutofillClientIdentity("com.other.browser", setOf("ABC123"))),
        )
        assertIs<AutofillClientTrust.Mismatch>(
            policy.evaluate(AutofillClientIdentity("com.example.browser", setOf("BAD999"))),
        )
        assertIs<AutofillClientTrust.Trusted>(
            policy.evaluate(AutofillClientIdentity("com.example.browser", setOf("ABC123"))),
        )
    }
}
