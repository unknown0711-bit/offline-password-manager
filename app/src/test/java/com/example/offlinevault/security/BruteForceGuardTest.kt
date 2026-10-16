package com.example.offlinevault.security

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BruteForceGuardTest {
    @Test
    fun appliesRequestedTiersAndReappliesPendingLockoutAfterReboot() {
        val preferences = MemoryPreferences()
        val clock = FakeBootClock()
        val guard = BruteForceGuard(preferences, clock = clock)

        repeat(5) { guard.recordFailure() }
        assertTrue(guard.state().isLocked)
        assertEquals(30_000L, guard.state().remainingLockoutMillis)

        clock.elapsed += 30_000L
        assertFalse(guard.state().isLocked)
        val sixth = guard.recordFailure()
        assertEquals(6, sixth.failureCount)
        assertEquals(60_000L, sixth.remainingLockoutMillis)

        clock.boot += 1
        clock.elapsed = 0L
        val afterReboot = guard.state()
        assertTrue(afterReboot.isLocked)
        assertEquals(60_000L, afterReboot.remainingLockoutMillis)
        assertEquals(1L, afterReboot.lockoutBootCount)
    }

    private class FakeBootClock : BootClock {
        var elapsed: Long = 0L
        var boot: Long = 0L
        override fun elapsedRealtimeMillis(): Long = elapsed
        override fun bootCount(): Long = boot
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class MemoryEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor = put(key, value?.toSet())
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean = applyChanges()
            override fun apply() { applyChanges() }

            private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
                pending[key] = value
                removals -= key
            }

            private fun applyChanges(): Boolean {
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }
        }
    }
}
