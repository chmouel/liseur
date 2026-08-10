package com.chmouel.liseur.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.liseursync.SyncAccountRepository
import com.chmouel.liseur.data.liseursync.SyncSetupFailure
import com.chmouel.liseur.data.liseursync.SyncSetupResult
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which way the reader is proving who they are. */
enum class SyncSignIn {
    /** Username and password, which mints a device token. */
    PASSWORD,

    /** A device token made on the server and pasted in. */
    TOKEN,
}

/** What went wrong connecting, phrased as something to act on. */
enum class SyncAccountError {
    BAD_CREDENTIALS,
    WRONG_SERVER,
    UNREACHABLE,
    UNREACHABLE_TRY_HTTP,
    INSECURE_TRANSPORT,
    RATE_LIMITED,
}

data class SyncServerUiState(
    val account: SyncAccount? = null,
    val signIn: SyncSignIn = SyncSignIn.PASSWORD,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val wantInsights: Boolean = true,
    val connecting: Boolean = false,
    val error: SyncAccountError? = null,
    val syncStatus: PositionSyncStatus = PositionSyncStatus.Idle,
) {
    /** Whether there is enough typed in to be worth trying. */
    val canConnect: Boolean
        get() = !connecting && url.isNotBlank() && username.isNotBlank() && when (signIn) {
            SyncSignIn.PASSWORD -> password.isNotBlank()
            SyncSignIn.TOKEN -> token.isNotBlank()
        }
}

class SyncServerViewModel(
    private val repository: SyncAccountRepository,
    private val reporting: SyncReporting,
    private val positionSync: PositionSyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncServerUiState())
    val state: StateFlow<SyncServerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.account.collect { account ->
                _state.update { it.copy(account = account) }
            }
        }
        viewModelScope.launch {
            reporting.status.collect { status ->
                _state.update { it.copy(syncStatus = status) }
            }
        }
    }

    fun setSignIn(value: SyncSignIn) = _state.update { it.copy(signIn = value, error = null) }
    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun setToken(value: String) = _state.update { it.copy(token = value.trim(), error = null) }
    fun setWantInsights(value: Boolean) = _state.update { it.copy(wantInsights = value) }

    /**
     * Connects, and on success forgets everything that was typed.
     *
     * The password in particular: it has already bought the device token
     * it exists to buy, and leaving it in a field on a screen someone can
     * come back to is keeping it for no reason at all.
     */
    fun connect(allowHttp: Boolean = false) {
        val current = _state.value
        if (!current.canConnect) return
        _state.update { it.copy(connecting = true, error = null) }

        viewModelScope.launch {
            val result = when (current.signIn) {
                SyncSignIn.PASSWORD -> repository.connect(
                    rawUrl = current.url,
                    username = current.username.trim(),
                    password = current.password,
                    wantInsights = current.wantInsights,
                    allowHttp = allowHttp,
                )

                SyncSignIn.TOKEN -> repository.connectWithToken(
                    rawUrl = current.url,
                    username = current.username.trim(),
                    token = current.token,
                    allowHttp = allowHttp,
                )
            }

            when (result) {
                is SyncSetupResult.Success -> {
                    _state.update {
                        it.copy(connecting = false, password = "", token = "", error = null)
                    }
                    positionSync.request(SyncScope.Full)
                }

                is SyncSetupResult.Failure ->
                    _state.update { it.copy(connecting = false, error = result.reason.asError()) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun syncNow() {
        viewModelScope.launch { positionSync.request(SyncScope.Full) }
    }

    private fun SyncSetupFailure.asError(): SyncAccountError = when (this) {
        SyncSetupFailure.BadCredentials -> SyncAccountError.BAD_CREDENTIALS
        SyncSetupFailure.WrongServer -> SyncAccountError.WRONG_SERVER
        SyncSetupFailure.InsecureTransport -> SyncAccountError.INSECURE_TRANSPORT
        SyncSetupFailure.RateLimited -> SyncAccountError.RATE_LIMITED
        is SyncSetupFailure.Unreachable ->
            if (httpMayWork) {
                SyncAccountError.UNREACHABLE_TRY_HTTP
            } else {
                SyncAccountError.UNREACHABLE
            }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                SyncServerViewModel(
                    repository = container.syncAccount,
                    reporting = container.syncReporting,
                    positionSync = container.positionSync,
                )
            }
        }
    }
}
