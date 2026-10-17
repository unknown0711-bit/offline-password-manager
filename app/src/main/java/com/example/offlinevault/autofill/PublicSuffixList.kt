package com.example.offlinevault.autofill

import android.content.res.AssetManager
import java.io.InputStreamReader
import java.io.Reader
import java.net.IDN
import java.nio.charset.StandardCharsets

/**
 * A small, offline Public Suffix List parser supporting exact, wildcard, and
 * exception rules from the Mozilla PSL format.
 *
 * The production asset is intentionally not created here because the Phase 2
 * file scope excludes res/assets. Integrators must package the current PSL at
 * [ASSET_PATH] (for example, `app/src/main/assets/autofill/public_suffix_list.dat`).
 * No network fallback is attempted when that asset is absent.
 */
public class PublicSuffixList private constructor(
    private val exactRules: Set<String>,
    private val wildcardRules: Set<String>,
    private val exceptionRules: Set<String>,
) {
    /** Returns the registrable domain (eTLD+1), or null for a public suffix/IP. */
    public fun etldPlusOne(value: String): String? {
        val host = normalizeHost(value) ?: return null
        if (isIpAddress(host)) return null
        val labels = host.split('.')
        if (labels.size < 2) return null

        val exception = exceptionRules
            .asSequence()
            .filter { host == it || host.endsWith(".$it") }
            .maxByOrNull { it.count { character -> character == '.' } }
        val publicSuffixLabels = if (exception != null) {
            // PSL exception rules remove their left-most label from the suffix.
            exception.count { it == '.' } + 1 - 1
        } else {
            var best = 1 // The PSL implicit "*" rule.
            exactRules.forEach { rule ->
                if (host == rule || host.endsWith(".$rule")) {
                    best = maxOf(best, rule.count { it == '.' } + 1)
                }
            }
            wildcardRules.forEach { rule ->
                if (host == rule || host.endsWith(".$rule")) {
                    // A wildcard rule is "*.rule", so it contributes one label.
                    best = maxOf(best, rule.count { it == '.' } + 2)
                }
            }
            best
        }
        if (labels.size <= publicSuffixLabels) return null
        return labels.takeLast(publicSuffixLabels + 1).joinToString(".")
    }

    public fun matchesSameSite(first: String, second: String): Boolean =
        etldPlusOne(first)?.equals(etldPlusOne(second), ignoreCase = true) == true

    public companion object {
        public const val ASSET_PATH: String = "autofill/public_suffix_list.dat"

        public fun fromReader(reader: Reader): PublicSuffixList {
            val exact = linkedSetOf<String>()
            val wildcard = linkedSetOf<String>()
            val exceptions = linkedSetOf<String>()
            reader.forEachLine { raw ->
                val line = raw.removePrefix("\uFEFF").trim()
                if (line.isEmpty() || line.startsWith("//")) return@forEachLine
                val ruleText = line.substringBefore(" //").trim()
                if (ruleText.isEmpty()) return@forEachLine
                val exception = ruleText.startsWith('!')
                val withoutException = ruleText.removePrefix("!")
                val wildcardRule = withoutException.startsWith("*.")
                val normalized = normalizeRule(withoutException.removePrefix("*.")) ?: return@forEachLine
                when {
                    exception -> exceptions += normalized
                    wildcardRule -> wildcard += normalized
                    else -> exact += normalized
                }
            }
            return PublicSuffixList(exact, wildcard, exceptions)
        }

        public fun fromText(text: String): PublicSuffixList =
            fromReader(text.reader())

        public fun fromAssets(assetManager: AssetManager, path: String = ASSET_PATH): PublicSuffixList =
            assetManager.open(path).use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use(::fromReader)
            }

        private fun normalizeRule(rule: String): String? = runCatching {
            IDN.toASCII(rule.trim().trim('.')).lowercase().takeIf { it.isNotEmpty() }
        }.getOrNull()

        private fun normalizeHost(value: String): String? {
            var candidate = value.trim().lowercase()
            if (candidate.isEmpty()) return null
            candidate = candidate.substringAfter("://", candidate)
            candidate = candidate.substringBefore('/').substringBefore('?').substringBefore('#')
            candidate = candidate.substringAfterLast('@')
            if (candidate.startsWith('[')) {
                candidate = candidate.substringBefore(']') + "]"
            } else if (candidate.count { it == ':' } == 1) {
                candidate = candidate.substringBefore(':')
            }
            candidate = candidate.trimEnd('.').removePrefix("[").removeSuffix("]")
            return runCatching { IDN.toASCII(candidate).lowercase().takeIf { it.isNotEmpty() } }.getOrNull()
        }

        private fun isIpAddress(host: String): Boolean {
            if (host.contains(':')) return true
            val pieces = host.split('.')
            return pieces.size == 4 && pieces.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true }
        }
    }
}
