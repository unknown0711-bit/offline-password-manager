package com.example.offlinevault.autofill

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

public data class AutofillClientIdentity(
    public val packageName: String,
    public val certificateSha256: Set<String>,
)

public sealed interface AutofillClientTrust {
    public data object Trusted : AutofillClientTrust

    public data class Mismatch(
        public val reason: String,
    ) : AutofillClientTrust
}

/** Resolves and checks the package that owns the current autofill structure. */
public interface AutofillClientTrustVerifier {
    public fun verify(packageName: String): AutofillClientTrust
}

/** A policy that can pin package names to one or more signing certificate digests. */
public interface AutofillClientTrustPolicy {
    public fun evaluate(identity: AutofillClientIdentity): AutofillClientTrust
}

/**
 * Strict package/certificate validation for autofill destinations.
 *
 * An empty pin map still validates that a real installed package has a signing
 * certificate. When a package is present in [certificatePins], every reported
 * signer must be one of its pinned SHA-256 digests and at least one signer must
 * match. This keeps the default usable for normal installed clients while
 * allowing a caller to fail closed for a high-assurance allowlist.
 */
public class PackageSignatureTrustPolicy(
    private val packageManager: PackageManager,
    private val certificatePins: Map<String, Set<String>> = emptyMap(),
) : AutofillClientTrustVerifier {
    override fun verify(packageName: String): AutofillClientTrust {
        if (packageName.isBlank()) {
            return AutofillClientTrust.Mismatch("The client package name is empty")
        }

        val installed = try {
            packageManager.getPackageInfo(packageName, signingInfoFlags())
        } catch (_: PackageManager.NameNotFoundException) {
            return AutofillClientTrust.Mismatch("The client package is not installed")
        } catch (_: RuntimeException) {
            return AutofillClientTrust.Mismatch("The client package signature could not be read")
        }
        val installedIdentity = installed.toClientIdentity(packageName)
            ?: return AutofillClientTrust.Mismatch("The client package has no signing certificate")
        val actual = installedIdentity.certificateSha256
        val pins = certificatePins[packageName]
        if (pins != null && (pins.isEmpty() || actual.intersect(pins).isEmpty())) {
            return AutofillClientTrust.Mismatch("The client package signature does not match its pin")
        }
        return AutofillClientTrust.Trusted
    }

    private fun signingInfoFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
}

/** Pure policy useful to callers that already obtained a verified package identity. */
public class PinnedAutofillClientTrustPolicy(
    private val certificatePins: Map<String, Set<String>>,
) : AutofillClientTrustPolicy, AutofillClientTrustVerifier {
    override fun verify(packageName: String): AutofillClientTrust =
        AutofillClientTrust.Mismatch("A package identity is required before checking a certificate pin")

    override fun evaluate(identity: AutofillClientIdentity): AutofillClientTrust {
        val expected = certificatePins[identity.packageName]
            ?: return AutofillClientTrust.Mismatch("The client package is not allowlisted")
        return if (identity.certificateSha256.intersect(expected).isNotEmpty()) {
            AutofillClientTrust.Trusted
        } else {
            AutofillClientTrust.Mismatch("The client package signature does not match its pin")
        }
    }
}

private fun PackageInfo.toClientIdentity(packageName: String): AutofillClientIdentity? {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        signingInfo?.apkContentsSigners?.toList().orEmpty()
    } else {
        @Suppress("DEPRECATION")
        signatures?.toList().orEmpty()
    }
    if (signatures.isEmpty()) return null
    val digests = signatures.mapNotNull { signature ->
        runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHex()
        }.getOrNull()
    }.toSet()
    return digests.takeIf { it.isNotEmpty() }?.let { AutofillClientIdentity(packageName, it) }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02X".format(byte) }
