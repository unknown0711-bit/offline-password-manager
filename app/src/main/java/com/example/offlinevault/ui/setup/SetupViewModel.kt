package com.example.offlinevault.ui.setup

import androidx.lifecycle.ViewModel
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates first-launch setup without persisting or exposing password state.
 * Hosts consume setup material synchronously and should hand it directly to the
 * security/data layer rather than retaining it.
 */
public class SetupViewModel(
    private val recoveryPhraseSource: RecoveryPhraseSource,
    private val random: SecureRandom = SecureRandom(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupUiState())
    public val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    private var masterPassword = CharArray(0)
    private var passwordConfirmation = CharArray(0)

    /** Updates only the strength preview; the supplied chars are not retained. */
    public fun previewMasterPassword(value: CharArray) {
        try {
            publish(mutableState.value.copy(
                passwordStrength = MasterPasswordStrengthEstimator.estimate(value),
                passwordError = null,
                error = null,
            ))
        } finally {
            value.fill('\u0000')
        }
    }

    /** Updates only the match preview against the submitted master password. */
    public fun previewPasswordConfirmation(value: CharArray) {
        try {
            publish(mutableState.value.copy(
                passwordsMatch = value.contentEquals(masterPassword),
                passwordError = null,
                error = null,
            ))
        } finally {
            value.fill('\u0000')
        }
    }

    public fun submitMasterPassword(value: CharArray) {
        replaceMasterPassword(value)
        publish(mutableState.value.copy(
            passwordStrength = MasterPasswordStrengthEstimator.estimate(masterPassword),
            passwordsMatch = passwordConfirmation.contentEquals(masterPassword),
            passwordError = null,
            error = null,
        ))
    }

    public fun submitPassword(value: CharArray): Unit = submitMasterPassword(value)

    public fun submitPasswordConfirmation(value: CharArray) {
        replacePasswordConfirmation(value)
        publish(mutableState.value.copy(
            passwordsMatch = passwordConfirmation.contentEquals(masterPassword),
            passwordError = null,
            error = null,
        ))
    }

    /** Creates the phrase once and enters the one-time recovery display. */
    public fun continueToRecovery(): Boolean {
        val current = mutableState.value
        if (current.step != SetupStep.PASSWORD) return false
        if (masterPassword.isEmpty()) {
            publish(current.copy(passwordError = "Master password is required"))
            return false
        }
        if (masterPassword.size < MasterPasswordStrengthEstimator.MIN_RECOMMENDED_LENGTH) {
            publish(current.copy(passwordError = "Use at least 12 characters"))
            return false
        }
        if (!masterPassword.contentEquals(passwordConfirmation)) {
            publish(current.copy(passwordError = "Master passwords do not match"))
            return false
        }

        val generated = try {
            recoveryPhraseSource.generate()
        } catch (error: Throwable) {
            publish(current.copy(error = error.message ?: "Unable to create recovery phrase"))
            return false
        }
        val words = try {
            splitWords(generated)
        } catch (error: Throwable) {
            publish(current.copy(error = error.message ?: "Recovery phrase must contain 24 words"))
            return false
        } finally {
            generated.fill('\u0000')
        }

        val indexes = chooseConfirmationIndexes(words.size)
        publish(SetupUiState(
            step = SetupStep.RECOVERY,
            passwordStrength = current.passwordStrength,
            passwordsMatch = true,
            recoveryPhrase = words,
            confirmationIndexes = indexes,
        ))
        return true
    }

    public fun proceedToRecovery(): Boolean = continueToRecovery()

    /** Confirms one of the three randomly selected word slots. */
    public fun confirmRecoveryWord(slot: Int, value: CharArray): Boolean {
        return try {
            val current = mutableState.value
            val wordIndex = current.confirmationIndexes.getOrNull(slot)
            val expected = wordIndex?.let { current.recoveryPhrase.getOrNull(it) }
            if (expected == null || !expected.contentEquals(value)) {
                publish(current.copy(recoveryError = "Word ${slot + 1} does not match"))
                false
            } else {
                val confirmed = current.confirmedSlots + slot
                publish(current.copy(
                    confirmedSlots = confirmed,
                    recoveryConfirmed = confirmed.size == current.confirmationIndexes.size,
                    recoveryError = null,
                ))
                true
            }
        } finally {
            value.fill('\u0000')
        }
    }

    /** Atomically checks all three confirmation answers and avoids partial UI state. */
    public fun confirmRecoveryWords(values: List<CharArray>): Boolean {
        val copies = values.map(CharArray::copyOf)
        return try {
            val current = mutableState.value
            if (current.confirmationIndexes.size != 3 || copies.size != 3) {
                publish(current.copy(recoveryError = "Enter all three recovery words"))
                return false
            }
            val matches = current.confirmationIndexes.indices.all { slot ->
                current.recoveryPhrase[current.confirmationIndexes[slot]].contentEquals(copies[slot])
            }
            if (!matches) {
                publish(current.copy(recoveryError = "One or more recovery words do not match"))
                false
            } else {
                publish(current.copy(
                    confirmedSlots = current.confirmationIndexes.indices.toSet(),
                    recoveryConfirmed = true,
                    recoveryError = null,
                ))
                true
            }
        } finally {
            copies.forEach { it.fill('\u0000') }
            values.forEach { it.fill('\u0000') }
        }
    }

    public fun submitRecoveryWord(slot: Int, value: CharArray): Boolean =
        confirmRecoveryWord(slot, value)

    /**
     * Delivers setup material once. Both callback arguments are wiped when the
     * callback returns, so the callback must synchronously hand them off.
     */
    public fun completeSetup(onSetupComplete: (masterPassword: CharArray, recoveryPhrase: CharArray) -> Unit): Boolean {
        val current = mutableState.value
        if (current.step != SetupStep.RECOVERY || !current.recoveryConfirmed) {
            publish(current.copy(recoveryError = "Confirm the three recovery words first"))
            return false
        }

        val passwordCopy = masterPassword.copyOf()
        val phraseCopy = joinWords(current.recoveryPhrase)
        try {
            onSetupComplete(passwordCopy, phraseCopy)
        } finally {
            passwordCopy.fill('\u0000')
            phraseCopy.fill('\u0000')
            clearSecrets()
            wipePhrase(current.recoveryPhrase)
            publish(current.copy(
                step = SetupStep.COMPLETE,
                recoveryPhrase = emptyList(),
                confirmationIndexes = emptyList(),
                confirmedSlots = emptySet(),
                recoveryConfirmed = false,
            ))
        }
        return true
    }

    public fun finishSetup(onSetupComplete: (CharArray, CharArray) -> Unit): Boolean =
        completeSetup(onSetupComplete)

    public fun reset() {
        clearSecrets()
        wipePhrase(mutableState.value.recoveryPhrase)
        publish(SetupUiState())
    }

    override fun onCleared() {
        clearSecrets()
        wipePhrase(mutableState.value.recoveryPhrase)
        super.onCleared()
    }

    private fun chooseConfirmationIndexes(size: Int): List<Int> {
        require(size == 24) { "A recovery phrase must contain exactly 24 words" }
        val selected = linkedSetOf<Int>()
        while (selected.size < 3) selected += random.nextInt(size)
        return selected.toList()
    }

    private fun splitWords(phrase: CharArray): List<CharArray> {
        val words = ArrayList<CharArray>(24)
        var start = -1
        for (index in 0..phrase.size) {
            val whitespace = index == phrase.size || phrase[index].isWhitespace()
            if (!whitespace && start < 0) start = index
            if (whitespace && start >= 0) {
                words += phrase.copyOfRange(start, index)
                start = -1
            }
        }
        require(words.size == 24) { "A recovery phrase must contain exactly 24 words" }
        return words
    }

    private fun joinWords(words: List<CharArray>): CharArray {
        val length = words.sumOf { it.size } + (words.size - 1).coerceAtLeast(0)
        val joined = CharArray(length)
        var offset = 0
        words.forEachIndexed { index, word ->
            word.copyInto(joined, destinationOffset = offset)
            offset += word.size
            if (index != words.lastIndex) joined[offset++] = ' '
        }
        return joined
    }

    private fun replaceMasterPassword(value: CharArray) {
        masterPassword.fill('\u0000')
        masterPassword = value.copyOf()
        value.fill('\u0000')
    }

    private fun replacePasswordConfirmation(value: CharArray) {
        passwordConfirmation.fill('\u0000')
        passwordConfirmation = value.copyOf()
        value.fill('\u0000')
    }

    private fun clearSecrets() {
        masterPassword.fill('\u0000')
        passwordConfirmation.fill('\u0000')
        masterPassword = CharArray(0)
        passwordConfirmation = CharArray(0)
    }

    private fun wipePhrase(words: List<CharArray>) {
        words.forEach { it.fill('\u0000') }
    }

    private fun publish(next: SetupUiState) {
        mutableState.value = next
    }
}

/** Descriptive alias for hosts that prefer the wizard-specific name. */
public typealias SetupWizardViewModel = SetupViewModel
