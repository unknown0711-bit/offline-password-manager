package com.example.offlinevault.login

import java.time.Clock
import java.time.Duration
import java.time.Instant

public enum class HealthSeverity { INFO, WARNING, CRITICAL }

public data class HealthFinding(
    public val entryUuid: String,
    public val title: String,
    public val severity: HealthSeverity,
    public val message: String,
)

public data class HealthReport(
    public val score: Int,
    public val findings: List<HealthFinding>,
    public val evaluatedCount: Int,
    public val reusedCount: Int,
)

/** Pure password-quality rules. It never stores or returns password material. */
public class PasswordHealthAnalyzer(
    private val clock: Clock = Clock.systemUTC(),
    private val oldPasswordAge: Duration = Duration.ofDays(365),
) {
    public fun analyze(inputs: List<LoginHealthInput>): HealthReport {
        val findings = mutableListOf<HealthFinding>()
        val fingerprints = mutableListOf<Pair<Int, LoginHealthInput>>()
        val now = Instant.now(clock).toEpochMilli()

        inputs.forEach { input ->
            val password = input.password
            try {
                val classes = passwordClassCount(password)
                when {
                    password.isEmpty() -> findings += HealthFinding(
                        input.entryUuid,
                        input.title,
                        HealthSeverity.CRITICAL,
                        "Password is empty",
                    )
                    password.size < 12 -> findings += HealthFinding(
                        input.entryUuid,
                        input.title,
                        HealthSeverity.CRITICAL,
                        "Use at least 12 characters",
                    )
                    classes < 3 -> findings += HealthFinding(
                        input.entryUuid,
                        input.title,
                        HealthSeverity.WARNING,
                        "Use three or more character classes",
                    )
                }
                if (now - input.updatedAt >= oldPasswordAge.toMillis()) {
                    findings += HealthFinding(
                        input.entryUuid,
                        input.title,
                        HealthSeverity.INFO,
                        "Password has not been changed recently",
                    )
                }
                fingerprints += passwordFingerprint(password) to input
            } finally {
                password.fill('\u0000')
            }
        }

        var reusedCount = 0
        fingerprints.groupBy { it.first }.values.forEach { sameFingerprint ->
            if (sameFingerprint.size < 2) return@forEach
            // A short non-cryptographic fingerprint is used only as a bucket; the
            // constant-time content comparison prevents false reuse findings.
            for (firstIndex in 0 until sameFingerprint.lastIndex) {
                for (secondIndex in firstIndex + 1 until sameFingerprint.size) {
                    val first = sameFingerprint[firstIndex].second.password
                    val second = sameFingerprint[secondIndex].second.password
                    try {
                        if (first.contentEquals(second)) {
                            reusedCount++
                            val firstInput = sameFingerprint[firstIndex].second
                            val secondInput = sameFingerprint[secondIndex].second
                            findings += HealthFinding(
                                firstInput.entryUuid,
                                firstInput.title,
                                HealthSeverity.CRITICAL,
                                "Password is reused by ${secondInput.title}",
                            )
                            findings += HealthFinding(
                                secondInput.entryUuid,
                                secondInput.title,
                                HealthSeverity.CRITICAL,
                                "Password is reused by ${firstInput.title}",
                            )
                        }
                    } finally {
                        first.fill('\u0000')
                        second.fill('\u0000')
                    }
                }
            }
        }

        val deduction = findings.fold(0) { total, finding ->
            total + if (finding.severity == HealthSeverity.CRITICAL) 18 else 7
        }
        return HealthReport(
            score = (100 - deduction).coerceIn(0, 100),
            findings = findings,
            evaluatedCount = inputs.size,
            reusedCount = reusedCount,
        )
    }

    private fun passwordClassCount(password: CharArray): Int = buildSet {
        password.forEach { char ->
            when {
                char.isUpperCase() -> add(0)
                char.isLowerCase() -> add(1)
                char.isDigit() -> add(2)
                else -> add(3)
            }
        }
    }.size

    private fun passwordFingerprint(password: CharArray): Int {
        var result = 1
        password.forEach { result = 31 * result + it.code }
        return result
    }
}
