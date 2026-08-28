package com.chmouel.liseur.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.kosync.KosyncSetupOutcome
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.BulkBatch
import com.chmouel.liseur.data.calibre.BulkDownloadEstimate
import com.chmouel.liseur.data.calibre.BulkStopReason
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
import com.chmouel.liseur.data.settings.UploadPolicy
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong while connecting, phrased as something to act on. */
enum class AccountError {
    BAD_CREDENTIALS,
    WRONG_SERVER,
    UNREACHABLE,
    UNREACHABLE_TRY_HTTP,
    INSECURE_TRANSPORT,
    INSUFFICIENT_SCOPES,
    RATE_LIMITED,
}

/** Which way a reader proves who they are to a liseur-sync server. */
enum class LiseurSyncSignIn {
    /** Username and password, which mint a device token. */
    PASSWORD,

    /** A device token made on the server and pasted in. */
    TOKEN,
}

/**
 * A book the server thought it recognised, but only by its title and
 * author.
 *
 * Two translations of the same novel look like this to a server, and so
 * do two editions with different text; exchanging positions between
 * them would land the reader in the wrong place. So nothing is
 * exchanged under a match like this until the reader has looked at it.
 */
data class WorkConfirmation(
    val bookUrl: String,
    val title: String,
    val author: String?,
)

data class ServerAccountUiState(
    val server: RemoteServer? = null,
    val kind: ServerKind = ServerKind.CALIBRE,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    /** The liseur-sync sign-in mode and its pasted-token field. */
    val liseurSyncSignIn: LiseurSyncSignIn = LiseurSyncSignIn.PASSWORD,
    val deviceToken: String = "",
    val connecting: Boolean = false,
    val error: AccountError? = null,
    val storage: StorageUse = StorageUse(count = 0, bytes = 0),
    val syncStatus: PositionSyncStatus = PositionSyncStatus.Idle,
    val syncReport: SyncReport = SyncReport(),
    val identity: SyncIdentity? = null,
    val lostToRestore: Boolean = false,
    /** Matches the server was not sure about, waiting on the reader. */
    val confirmations: List<WorkConfirmation> = emptyList(),
    /** Books whose identifiers named two different works on the server. */
    val ambiguities: Int = 0,
    /** What to do with a book that arrives on this device. */
    val uploadPolicy: UploadPolicy = UploadPolicy.Default,
    /** The bulk download that is running, or the last one's summary. */
    val bulkBatch: BulkBatch? = null,
    /** What a "download everything" would cost, once the reader asks. */
    val bulkEstimate: BulkDownloadEstimate? = null,
    /** True while the estimate is being worked out. */
    val estimating: Boolean = false,
    /** The KOReader sync partner paired alongside the catalog server. */
    val kosync: KosyncPeer? = null,
    val kosyncUrl: String = "",
    val kosyncUsername: String = "",
    val kosyncPassword: String = "",
    /** Whether connecting should create the account on the server first. */
    val kosyncRegister: Boolean = false,
    val kosyncConnecting: Boolean = false,
    val kosyncError: AccountError? = null,
    /** How the kosync partner's own last run went, apart from the summary. */
    val kosyncStatus: PositionSyncStatus = PositionSyncStatus.Idle,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAccountViewModel(
    private val repository: RemoteAccountRepository,
    private val downloads: BookDownloadRepository,
    private val reporting: SyncReporting,
    private val positionSync: PositionSyncCoordinator,
    private val catalog: RemoteCatalogRepository,
    private val appSettings: AppSettingsRepository,
    private val identityDao: WorkIdentityDao,
    private val bookDao: BookDao,
    private val kosyncAccount: KosyncAccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerAccountUiState())
    val state: StateFlow<ServerAccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Server and kosync peer arrive together: the Grimmory
            // prefill below must know whether a peer exists, and two
            // independent collectors would let it fire before the
            // peer's first emission.
            combine(repository.server, kosyncAccount.peer) { server, peer ->
                server to peer
            }.collect { (server, peer) ->
                _state.update { state ->
                    state.copy(
                        server = server,
                        kosync = peer,
                        kosyncUrl = kosyncPrefillUrl(server, peer, state.kosyncUrl)
                            ?: state.kosyncUrl,
                    )
                }
            }
        }
        viewModelScope.launch {
            downloads.storage.collect { use ->
                _state.update { it.copy(storage = use) }
            }
        }
        viewModelScope.launch {
            reporting.status.collect { status ->
                _state.update {
                    it.copy(
                        syncStatus = status,
                        kosyncStatus = reporting.statusOf(PeerPositionSync.KOSYNC),
                    )
                }
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
            appSettings.settings.collect { settings ->
                _state.update { it.copy(uploadPolicy = settings.uploadPolicy) }
            }
        }
        viewModelScope.launch {
            reporting.report.collect { report ->
                _state.update { it.copy(syncReport = report) }
            }
        }
        viewModelScope.launch {
            downloads.bulkBatch.collect { batch ->
                _state.update { it.copy(bulkBatch = batch) }
            }
        }
        viewModelScope.launch {
            // The questions a liseur-sync server could not answer on its
            // own. They belong to the account, so they are observed for
            // whichever account is connected, and there are none to
            // observe for the other kinds.
            repository.server.flatMapLatest { server ->
                if (server?.kind != ServerKind.LISEUR_SYNC) {
                    flowOf(emptyList<WorkConfirmation>() to 0)
                } else {
                    combine(
                        identityDao.observeAwaitingAnswer(server.accountKey),
                        identityDao.observeAmbiguityCount(server.accountKey),
                        bookDao.observeAll(),
                    ) { aliases, ambiguities, books ->
                        val byUrl = books.associateBy { it.url }
                        aliases.mapNotNull { alias ->
                            byUrl[alias.bookUrl]?.let {
                                WorkConfirmation(
                                    bookUrl = alias.bookUrl,
                                    title = it.displayTitle,
                                    author = it.displayAuthor,
                                )
                            }
                        } to ambiguities
                    }
                }
            }.collect { (confirmations, ambiguities) ->
                _state.update { it.copy(confirmations = confirmations, ambiguities = ambiguities) }
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

    fun setLiseurSyncSignIn(value: LiseurSyncSignIn) =
        _state.update { it.copy(liseurSyncSignIn = value, error = null) }

    fun setDeviceToken(value: String) =
        _state.update { it.copy(deviceToken = value.trim(), error = null) }

    fun setKosyncUrl(value: String) =
        _state.update { it.copy(kosyncUrl = value, kosyncError = null) }

    fun setKosyncUsername(value: String) =
        _state.update { it.copy(kosyncUsername = value, kosyncError = null) }

    fun setKosyncPassword(value: String) =
        _state.update { it.copy(kosyncPassword = value, kosyncError = null) }

    fun setKosyncRegister(value: Boolean) =
        _state.update { it.copy(kosyncRegister = value, kosyncError = null) }

    /** Pairs the KOReader sync partner, then asks it where everything is. */
    fun connectKosync() {
        val current = _state.value
        if (current.kosyncConnecting) return
        if (current.kosyncUrl.isBlank() ||
            current.kosyncUsername.isBlank() ||
            current.kosyncPassword.isBlank()
        ) {
            return
        }
        _state.update { it.copy(kosyncConnecting = true, kosyncError = null) }
        viewModelScope.launch {
            val outcome = kosyncAccount.connect(
                url = current.kosyncUrl,
                username = current.kosyncUsername,
                password = current.kosyncPassword,
                register = current.kosyncRegister,
            )
            _state.update {
                when (outcome) {
                    KosyncSetupOutcome.Success ->
                        it.copy(kosyncConnecting = false, kosyncPassword = "")
                    is KosyncSetupOutcome.Failure ->
                        it.copy(kosyncConnecting = false, kosyncError = outcome.reason.toUiError())
                }
            }
            if (outcome == KosyncSetupOutcome.Success) {
                positionSync.request(SyncScope.Full)
            }
        }
    }

    fun disconnectKosync() {
        viewModelScope.launch { kosyncAccount.disconnect() }
    }

    /**
     * Answers the "is this the same book?" question.
     *
     * A yes starts exchanging positions under that name on the next run.
     * A no is remembered rather than acted on and forgotten: the book
     * would otherwise be resolved again and the same question asked
     * again, forever.
     */
    fun answerConfirmation(bookUrl: String, sameBook: Boolean) {
        val peerId = _state.value.server
            ?.takeIf { it.kind == ServerKind.LISEUR_SYNC }
            ?.accountKey ?: return
        viewModelScope.launch {
            if (sameBook) {
                identityDao.confirm(bookUrl, peerId)
                positionSync.request(SyncScope.Full)
            } else {
                identityDao.reject(bookUrl, peerId)
            }
        }
    }

    fun connect(allowHttp: Boolean = false) {
        val current = _state.value
        if (current.connecting || current.url.isBlank()) return
        if (!current.credentialsSupplied()) return
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
                ServerKind.GRIMMORY -> repository.connectGrimmory(
                    url = current.url,
                    username = current.username.trim(),
                    password = current.password,
                    allowHttp = allowHttp,
                )
                ServerKind.LISEUR_SYNC -> when (current.liseurSyncSignIn) {
                    LiseurSyncSignIn.PASSWORD -> repository.connectLiseurSync(
                        url = current.url,
                        username = current.username.trim(),
                        password = current.password,
                        allowHttp = allowHttp,
                    )
                    LiseurSyncSignIn.TOKEN -> repository.connectLiseurSyncToken(
                        url = current.url,
                        token = current.deviceToken,
                        allowHttp = allowHttp,
                    )
                }
            }
            if (result is SetupResult.Success) {
                fetchCatalogAndPositions()
                appSettings.setAccountLostToRestore(false)
            }
            _state.update {
                when (result) {
                    is SetupResult.Success ->
                        it.copy(connecting = false, password = "", apiKey = "", deviceToken = "")
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
                fetchCatalogAndPositions()
            }
            _state.update { it.copy(connecting = false) }
        }
    }

    /**
     * A newly connected server's books, and then where they were read.
     *
     * Connecting is the one moment nothing about this account is known
     * yet, so both halves are worth doing at once -- and the catalog
     * walk that has just finished is handed to the sync rather than
     * being done again. Neither is tied to this screen, which is
     * usually gone the moment the account turns green.
     */
    private fun fetchCatalogAndPositions() {
        catalog.refreshDetached { refreshed ->
            if (!refreshed.completed) return@refreshDetached
            positionSync.request(
                SyncScope.Full,
                System.currentTimeMillis(),
                refreshed.forSync(),
            )
        }
    }

    fun setUploadPolicy(policy: UploadPolicy) {
        viewModelScope.launch { appSettings.setUploadPolicy(policy) }
    }

    fun setKoboToken(value: String) {
        viewModelScope.launch { repository.setKoboToken(value.ifBlank { null }) }
    }

    /** Whether enough of the form is filled in to be worth trying. */
    private fun ServerAccountUiState.credentialsSupplied(): Boolean = when (kind) {
        ServerKind.CALIBRE, ServerKind.GRIMMORY ->
            username.isNotBlank() && password.isNotBlank()
        ServerKind.KOMGA -> apiKey.isNotBlank()
        ServerKind.LISEUR_SYNC -> when (liseurSyncSignIn) {
            LiseurSyncSignIn.PASSWORD -> username.isNotBlank() && password.isNotBlank()
            LiseurSyncSignIn.TOKEN -> deviceToken.isNotBlank()
        }
    }

    /**
     * Works out what fetching everything would cost, and shows it.
     *
     * Reading free space touches the filesystem, so it is asked for
     * only when the reader reaches for the action rather than kept
     * fresh in the background.
     */
    fun askToDownloadAll() {
        if (_state.value.estimating) return
        _state.update { it.copy(estimating = true) }
        viewModelScope.launch {
            // Cleared however it goes. The flag is also what keeps a
            // second tap from starting a second estimate, so a throw
            // that left it standing would take the action with it until
            // the process was restarted.
            val estimate = runCatching { downloads.bulkEstimate() }
                .onFailure { Log.w(TAG, "could not work out what downloading everything would cost", it) }
                .getOrNull()
            _state.update { it.copy(estimating = false, bulkEstimate = estimate) }
        }
    }

    fun dismissDownloadAll() = _state.update { it.copy(bulkEstimate = null) }

    fun downloadAll() {
        val accountKey = _state.value.server?.accountKey ?: return
        _state.update { it.copy(bulkEstimate = null) }
        viewModelScope.launch { downloads.enqueueAll(accountKey) }
    }

    fun cancelDownloadAll() {
        viewModelScope.launch { downloads.cancelAll() }
    }

    /** Clears the summary of a batch that has ended. */
    fun dismissBatch() {
        viewModelScope.launch { downloads.dismissBatch() }
    }

    fun disconnect() {
        viewModelScope.launch {
            // Before the account goes: work queued against it would
            // otherwise be handed to whatever is connected next, and the
            // books it left mid-flight would sit queued forever.
            downloads.cancelAll(BulkStopReason.ACCOUNT_CHANGED)
            repository.disconnect()
            // The kosync partner stands on its own: its lifecycle is
            // deliberately not the catalog's, and its Room flow has
            // nothing new to re-emit after this reset.
            val kept = _state.value
            _state.value = ServerAccountUiState(
                kosync = kept.kosync,
                kosyncStatus = kept.kosyncStatus,
            )
        }
    }

    private fun SetupFailure.toUiError(): AccountError = when (this) {
        SetupFailure.BadCredentials -> AccountError.BAD_CREDENTIALS
        SetupFailure.InsufficientScopes -> AccountError.INSUFFICIENT_SCOPES
        SetupFailure.WrongServer -> AccountError.WRONG_SERVER
        SetupFailure.InsecureTransport -> AccountError.INSECURE_TRANSPORT
        SetupFailure.RateLimited -> AccountError.RATE_LIMITED
        is SetupFailure.Unreachable ->
            if (httpMayWork) AccountError.UNREACHABLE_TRY_HTTP else AccountError.UNREACHABLE
    }

    companion object {
        private const val TAG = "ServerAccountViewModel"

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
                    identityDao = container.database.workIdentityDao(),
                    bookDao = container.database.bookDao(),
                    kosyncAccount = container.kosyncAccount,
                )
            }
        }
    }
}

/**
 * Grimmory's own kosync mount, offered once.
 *
 * Only while the URL field is untouched and nothing is paired, so a
 * reader's typing is never overwritten and an existing pairing is never
 * disturbed; null means "leave the field as it is".
 */
internal fun kosyncPrefillUrl(
    server: RemoteServer?,
    peer: KosyncPeer?,
    currentUrl: String,
): String? {
    if (server?.kind != ServerKind.GRIMMORY) return null
    if (peer != null || currentUrl.isNotBlank()) return null
    return server.baseUrl.trimEnd('/') + "/api/koreader"
}
