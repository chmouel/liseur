package com.chmouel.liseur.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.calibre.CalibreAccountRepository
import com.chmouel.liseur.data.calibre.SetupFailure
import com.chmouel.liseur.data.calibre.SetupResult
import com.chmouel.liseur.data.db.CalibreServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong while connecting, phrased as something to act on. */
enum class AccountError {
    BAD_CREDENTIALS,
    NOT_CALIBRE_WEB,
    UNREACHABLE,
    UNREACHABLE_TRY_HTTP,
}

data class CalibreAccountUiState(
    val server: CalibreServer? = null,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val connecting: Boolean = false,
    val error: AccountError? = null,
)

class CalibreAccountViewModel(
    private val repository: CalibreAccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibreAccountUiState())
    val state: StateFlow<CalibreAccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.server.collect { server ->
                _state.update { it.copy(server = server) }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun connect(allowHttp: Boolean = false) {
        val current = _state.value
        if (current.connecting || current.url.isBlank()) return
        _state.update { it.copy(connecting = true, error = null) }

        viewModelScope.launch {
            val result = repository.connect(
                url = current.url,
                username = current.username.trim(),
                password = current.password,
                allowHttp = allowHttp,
            )
            _state.update {
                when (result) {
                    is SetupResult.Success -> it.copy(connecting = false, password = "")
                    is SetupResult.Failure ->
                        it.copy(connecting = false, error = result.reason.toUiError())
                }
            }
        }
    }

    /** Re-probes the saved account, e.g. after fixing a permission server-side. */
    fun retryCapabilities() {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            repository.refreshCapabilities()
            _state.update { it.copy(connecting = false) }
        }
    }

    fun setKoboToken(value: String) {
        viewModelScope.launch { repository.setKoboToken(value.ifBlank { null }) }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            _state.value = CalibreAccountUiState()
        }
    }

    private fun SetupFailure.toUiError(): AccountError = when (this) {
        SetupFailure.BadCredentials -> AccountError.BAD_CREDENTIALS
        SetupFailure.NotCalibreWeb -> AccountError.NOT_CALIBRE_WEB
        is SetupFailure.Unreachable ->
            if (httpMayWork) AccountError.UNREACHABLE_TRY_HTTP else AccountError.UNREACHABLE
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                CalibreAccountViewModel(container.calibreAccount)
            }
        }
    }
}
