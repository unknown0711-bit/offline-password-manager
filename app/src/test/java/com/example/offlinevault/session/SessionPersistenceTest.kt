package com.example.offlinevault.session

import android.content.SharedPreferences
import com.example.offlinevault.security.Argon2idParameters
import com.example.offlinevault.security.Argon2idWrappedDek
import com.example.offlinevault.security.Bip39WrappedDek
import com.example.offlinevault.security.BootClock
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.StrongBoxWrappedDek
import com.example.offlinevault.security.WrappedVaultKeys
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class SessionPersistenceTest {
    @Test
    public fun wrappedKeysRoundTripWithoutPersistingDek() {
        val preferences = FakeSharedPreferences()
        val store = EncryptedWrappedVaultKeyStore(preferences)
        val sourceDek = ByteArray(32) { 0x11 }
        val keys = WrappedVaultKeys(
            dek = DataEncryptionKey(sourceDek),
            masterPassword = Argon2idWrappedDek(
                salt = ByteArray(16) { 0x22 },
                nonce = ByteArray(12) { 0x23 },
                ciphertext = ByteArray(48) { 0x24 },
                parameters = Argon2idParameters(memoryKiB = 8, iterations = 1, parallelism = 1),
            ),
            biometric = StrongBoxWrappedDek(
                nonce = ByteArray(12) { 0x31 },
                ciphertext = ByteArray(48) { 0x32 },
                keyAlias = "test-strongbox",
            ),
            recoveryPhrase = Bip39WrappedDek(
                nonce = ByteArray(12) { 0x41 },
                ciphertext = ByteArray(48) { 0x42 },
            ),
        )
        try {
            store.write(keys)
            val persisted = store.read()
            assertNotNull(persisted)
            persisted!!.use {
                assertArrayEquals(ByteArray(16) { 0x22 }, it.masterPassword.salt)
                assertArrayEquals(ByteArray(48) { 0x24 }, it.masterPassword.ciphertext)
                assertArrayEquals(ByteArray(48) { 0x32 }, it.biometric.ciphertext)
                assertArrayEquals(ByteArray(48) { 0x42 }, it.recoveryPhrase.ciphertext)
                assertFalse(preferences.allValues().values.any { value ->
                    value is ByteArray && value.contentEquals(sourceDek)
                })
            }
        } finally {
            sourceDek.fill(0)
            keys.close()
        }
    }

    @Test
    public fun biometricGateOnlyAllowsTheBootThatSawThePassword() {
        val preferences = FakeSharedPreferences()
        val clock = FakeBootClock(boot = 7L)
        val gate = MasterPasswordBootGate(preferences, clock)

        assertFalse(gate.hasMasterPasswordSinceCurrentBoot())
        gate.markMasterPasswordEntered()
        assertTrue(gate.hasMasterPasswordSinceCurrentBoot())

        clock.boot = 8L
        assertFalse(gate.hasMasterPasswordSinceCurrentBoot())
        clock.boot = 7L
        assertTrue(gate.hasMasterPasswordSinceCurrentBoot())
    }

    private class FakeBootClock(var boot: Long) : BootClock {
        override fun elapsedRealtimeMillis(): Long = 0L
        override fun bootCount(): Long = boot
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        fun allValues(): Map<String, Any?> = values.toMap()

        override fun getAll(): Map<String, *> = Collections.unmodifiableMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
                updates[key] = values?.toSet()
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                updates[key] = REMOVED
            }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value === REMOVED || value == null) values.remove(key) else values[key] = value
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        private companion object {
            val REMOVED = Any()
        }
    }
}
