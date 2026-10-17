package com.example.offlinevault.ui.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupViewModelTest {
    @Test
    fun createsOnePhraseAndRequiresTheThreeSelectedWords() {
        val phrase = (1..24).joinToString(" ") { "word$it" }
        val viewModel = SetupViewModel(FixedRecoveryPhraseSource(phrase.toCharArray()))

        viewModel.submitMasterPassword("A-long-master-password-42".toCharArray())
        viewModel.submitPasswordConfirmation("A-long-master-password-42".toCharArray())
        assertTrue(viewModel.continueToRecovery())
        val state = viewModel.state.value
        assertEquals(SetupStep.RECOVERY, state.step)
        assertEquals(3, state.confirmationIndexes.distinct().size)
        assertTrue(state.confirmationIndexes.all { it in 0..23 })

        val answers = state.confirmationIndexes.map { index -> "word${index + 1}".toCharArray() }
        assertTrue(viewModel.confirmRecoveryWords(answers))

        var callbackPassword = ""
        var callbackPhrase = ""
        assertTrue(viewModel.completeSetup { password, recovery ->
            callbackPassword = password.concatToString()
            callbackPhrase = recovery.concatToString()
        })
        assertEquals("A-long-master-password-42", callbackPassword)
        assertEquals(phrase, callbackPhrase)
        assertEquals(SetupStep.COMPLETE, viewModel.state.value.step)
        assertTrue(viewModel.state.value.recoveryPhrase.isEmpty())
    }

    @Test
    fun rejectsWeakOrMismatchedMasterPasswordBeforeShowingPhrase() {
        val viewModel = SetupViewModel(FixedRecoveryPhraseSource(CharArray(24) { 'a' }))
        viewModel.submitMasterPassword("short".toCharArray())
        viewModel.submitPasswordConfirmation("different".toCharArray())

        assertTrue(!viewModel.continueToRecovery())
        assertEquals(SetupStep.PASSWORD, viewModel.state.value.step)
        assertEquals("Use at least 12 characters", viewModel.state.value.passwordError)
    }
}
