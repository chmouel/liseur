package com.chmouel.liseur.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.data.calibre.StorageUse
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong while connecting, phrased as something to act on. */
enum class AccountError {
    BAD_CREDENTIALS,
    WRONG_SERVER,
    UNREACHABLE,
    UNREACHABLE_TRY_HTTP,
}

data class ServerAccountUiState(
    val server: RemoteServer? = null,
    val kind: ServerKind = ServerKind.CALIBRE,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val connecting: Boolean = false,
    val error: AccountError? = null,
    val storage: StorageUse = StorageUse(count = 0, bytes = 0),
    val syncStatus: PositionSyncStatus = PositionSyncStatus.Idle,
    val syncReport: SyncReport = SyncReport(),
    val identity: SyncIdentity? = null,
    val lostToRestore: Boolean = false,
)

class ServerAccountViewModel(
    private val repository: RemoteAccountRepository,
    downloads: BookDownloadRepository,
    private val reporting: SyncReporting,
    private val positionSync: PositionSyncCoordinator,
    private val catalog: RemoteCatalogRepository,
    private val appSettings: AppSettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerAccountUiState())
    val state: StateFlow<ServerAccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.server.collect { server ->
                _state.update { it.copy(server = server) }
            }
        }
        viewModelScope.launch {
            downloads.storage.collect { use ->
                _state.update { it.copy(storage = use) }
            }
        }
        viewModelScope.launch {
            reporting.status.collect { status ->
                _state.update { it.copy(syncStatus = status) }
                // Every settled run can change who owns what and what is
                // left over, so the answer is re-read rather than cached.
                refreshDiagnostics()
            }
        }
        viewModelScope.launch {
            appSettings.accountLostToRestore.collect { lost ->
                _state.update { it.copy(lostToRestore = lost) }
            }
        }
        viewModelScope.launch {
            reporting.report.collect { report ->
                _state.update { it.copy(syncReport = report) }
            }
        }
    }

    private suspend fun refreshDiagnostics() {
        positionSync.refreshUnresolved()
        _state.update { it.copy(identity = positionSync.identity()) }
    }

    /** Reconciles reading positions now, for the "Sync now" button. */
    fun syncPositions() {
        viewModelScope.launch { positionSync.request(SyncScope.Full) }
    }

    fun setKind(value: ServerKind) = _state.update { it.copy(kind = value, error = null) }

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null) }

    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun connect(allowHttp: Boolean = false) {
        val current = _state.value
        if (current.connecting || current.url.isBlank()) return
        if (current.kind == ServerKind.KOMGA && current.apiKey.isBlank()) return
        _state.update { it.copy(connecting = true, error = null) }

        viewModelScope.launch {
            val result = when (current.kind) {
                ServerKind.CALIBRE -> repository.connectCalibre(
                    url = current.url,
                    username = current.username.trim(),
                    password = current.password,
                    allowHttp = allowHttp,
                )
                ServerKind.KOMGA -> repository.connectKomga(
                    url = current.url,
                    apiKey = current.apiKey.trim(),
                    allowHttp = allowHttp,
                )
            }
            if (result is SetupResult.Success) {
                catalog.refreshDetached()
                appSettings.setAccountLostToRestore(false)
            }
            _state.update {
                when (result) {
                    is SetupResult.Success ->
                        it.copy(connecting = false, password = "", apiKey = "")
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
            if (repository.refreshCapabilities() is SetupResult.Success) {
                catalog.refreshDetached()
            }
            _state.update { it.copy(connecting = false) }
        }
    }

    fun setKoboToken(value: String) {
        viewModelScope.launch { repository.setKoboToken(value.ifBlank { null }) }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            _state.value = ServerAccountUiState()
        }
    }

    private fun SetupFailure.toUiError(): AccountError = when (this) {
        SetupFailure.BadCredentials -> AccountError.BAD_CREDENTIALS
        SetupFailure.WrongServer -> AccountError.WRONG_SERVER
        is SetupFailure.Unreachable ->
            if (httpMayWork) AccountError.UNREACHABLE_TRY_HTTP else AccountError.UNREACHABLE
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                ServerAccountViewModel(
                    repository = container.remoteAccount,
                    downloads = container.bookDownloads,
                    reporting = container.syncReporting,
                    positionSync = container.positionSync,
                    catalog = container.remoteCatalog,
                    appSettings = container.appSettings,
                )
            }
        }
    }
}
