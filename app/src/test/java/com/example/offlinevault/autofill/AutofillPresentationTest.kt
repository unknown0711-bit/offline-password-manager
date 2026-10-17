package com.example.offlinevault.autofill

import kotlin.test.Test
import kotlin.test.assertEquals

class AutofillPresentationTest {
    @Test
    fun masksLocalPartButKeepsEmailDomain() {
        assertEquals("alice***", AutofillPresentation.maskUsername("alice"))
        assertEquals("alice***@example.com", AutofillPresentation.maskUsername("alice@example.com"))
        assertEquals("dev.u***@mail.com", AutofillPresentation.maskUsername("dev.user@mail.com"))
        assertEquals("a*", AutofillPresentation.maskUsername("ab"))
        assertEquals("*", AutofillPresentation.maskUsername("a"))
    }
}
