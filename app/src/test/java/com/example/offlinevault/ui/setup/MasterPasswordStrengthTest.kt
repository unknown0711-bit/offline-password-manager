package com.example.offlinevault.ui.setup

import kotlin.test.Test
import kotlin.test.assertEquals

class MasterPasswordStrengthTest {
    @Test
    fun emptyAndVariedPasswordsHaveUsefulLabels() {
        assertEquals(PasswordStrength.EMPTY, MasterPasswordStrengthEstimator.estimate(CharArray(0)).strength)
        assertEquals(
            PasswordStrength.STRONG,
            MasterPasswordStrengthEstimator.estimate("Longer-master-password-42".toCharArray()).strength,
        )
    }
}
