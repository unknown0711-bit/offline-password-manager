package com.example.offlinevault.login

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordHealthAnalyzerTest {
    @Test
    fun reports_reuse_short_passwords_and_old_passwords_without_returning_secrets() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val analyzer = PasswordHealthAnalyzer(Clock.fixed(now, ZoneOffset.UTC))
        val first = LoginHealthInput("one", "One", "short".toCharArray(), now.minusSeconds(366L * 86_400).toEpochMilli())
        val second = LoginHealthInput("two", "Two", "short".toCharArray(), now.toEpochMilli())
        try {
            val report = analyzer.analyze(listOf(first, second))
            assertEquals(2, report.evaluatedCount)
            assertEquals(1, report.reusedCount)
            assertTrue(report.findings.any { it.message.contains("reused") })
            assertTrue(report.findings.none { it.message.contains("short") && it.message.length > 20 })
            assertTrue(report.findings.any { it.message.contains("recently") })
        } finally {
            first.close()
            second.close()
        }
    }
}
