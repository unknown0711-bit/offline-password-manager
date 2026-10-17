package com.example.offlinevault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinevault.login.LoginDraft
import com.example.offlinevault.login.LoginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class LoginEditorState(
    public val title: String = "",
    public val username: String = "",
    public val url: String = "",
    public val tags: String = "",
    public val saving: Boolean = false,
    public val savedEntryUuid: String? = null,
    public val error: String? = null,
)

/** New-login editor. Metadata is UI text; the password is retained only as CharArray. */
public class LoginEditorViewModel(
    private val repository: LoginRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginEditorState())
    public val state: StateFlow<LoginEditorState> = mutableState.asStateFlow()
    private var password: CharArray = CharArray(0)

    public fun setTitle(value: String) { mutableState.value = mutableState.value.copy(title = value) }
    public fun setUsername(value: String) { mutableState.value = mutableState.value.copy(username = value) }
    public fun setUrl(value: String) { mutableState.value = mutableState.value.copy(url = value) }
    public fun setTags(value: String) { mutableState.value = mutableState.value.copy(tags = value) }

    public fun setPassword(value: CharArray) {
        password.fill('\u0000')
        password = value.copyOf()
    }

    public fun save() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            val draftPassword = password.copyOf()
            var draft: LoginDraft? = null
            try {
                draft = LoginDraft(
                    entryUuid = null,
                    title = mutableState.value.title.trim(),
                    username = mutableState.value.username,
                    password = draftPassword,
                    tags = mutableState.value.tags.split(',').map(String::trim).filter(String::isNotEmpty),
                    url = mutableState.value.url.trim(),
                )
                val id = repository.saveLogin(draft)
                mutableState.value = mutableState.value.copy(saving = false, savedEntryUuid = id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    saving = false,
                    error = error.message ?: "Unable to save login",
                )
            } finally {
                draft?.close()
                draftPassword.fill('\u0000')
            }
        }
    }

    override fun onCleared() {
        password.fill('\u0000')
        password = CharArray(0)
        super.onCleared()
    }
}
