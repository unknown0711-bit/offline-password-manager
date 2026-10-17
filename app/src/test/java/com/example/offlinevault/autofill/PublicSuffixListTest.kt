package com.example.offlinevault.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicSuffixListTest {
    private val list = PublicSuffixList.fromText(
        """
        // comment
        com
        co.uk
        *.ck
        !www.ck
        """.trimIndent(),
    )

    @Test
    fun computesEtldPlusOneForExactAndWildcardRules() {
        assertEquals("example.com", list.etldPlusOne("https://login.example.com/path"))
        assertEquals("example.co.uk", list.etldPlusOne("shop.example.co.uk"))
        assertEquals("a.b.ck", list.etldPlusOne("a.b.ck"))
    }

    @Test
    fun exceptionRuleAndIpAddressesAreHandled() {
        assertEquals("www.ck", list.etldPlusOne("www.ck"))
        assertNull(list.etldPlusOne("192.0.2.5"))
        assertNull(list.etldPlusOne("co.uk"))
    }
}
