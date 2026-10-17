package com.example.offlinevault.media

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaPathPolicyTest {
    private val policy = MediaPathPolicy(Paths.get("/private/files/vault"))

    @Test
    fun onlyUuidNamesResolveDirectlyUnderVault() {
        val fileName = policy.newFileName()
        assertTrue(MediaPathPolicy.isUuidOnlyFileName(fileName))
        assertEquals(policy.vaultDirectory, policy.resolve(fileName).parent)
        assertFailsWith<IllegalArgumentException> { policy.resolve("holiday.jpg") }
        assertFailsWith<IllegalArgumentException> { policy.resolve("../$fileName") }
        assertFailsWith<IllegalArgumentException> {
            policy.validateExisting(Paths.get("/private/files/$fileName"))
        }
    }
}
