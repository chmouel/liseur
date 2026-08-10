package com.chmouel.liseur.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.liseursync.SyncAccountRepository
import com.chmouel.liseur.data.liseursync.SyncSetupFailure
import com.chmouel.liseur.data.liseursync.SyncSetupResult
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    val confirmations: List<WorkConfirmation> = emptyList(),
    /** Books whose identifiers named two different works on the server. */
    val ambiguities: Int = 0,
) {
    /** Whether there is enough typed in to be worth trying. */
    val canConnect: Boolean
        get() = !connecting && url.isNotBlank() && username.isNotBlank() && when (signIn) {
            SyncSignIn.PASSWORD -> password.isNotBlank()
            SyncSignIn.TOKEN -> token.isNotBlank()
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncServerViewModel(
    private val repository: SyncAccountRepository,
    private val reporting: SyncReporting,
    private val positionSync: PositionSyncCoordinator,
    private val identityDao: WorkIdentityDao,
    private val bookDao: BookDao,
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
        viewModelScope.launch {
            repository.account.flatMapLatest { account ->
                if (account == null) {
                    flowOf(emptyList<WorkConfirmation>() to 0)
                } else {
                    combine(
                        identityDao.observeAwaitingAnswer(account.peerId),
                        identityDao.observeAmbiguityCount(account.peerId),
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

    /**
     * Answers the "is this the same book?" question.
     *
     * A yes starts exchanging positions under that name on the next run.
     * A no is remembered rather than acted on and forgotten: the book
     * would otherwise be resolved again and the same question asked
     * again, forever.
     */
    fun answerConfirmation(bookUrl: String, sameBook: Boolean) {
        val peerId = _state.value.account?.peerId ?: return
        viewModelScope.launch {
            if (sameBook) {
                identityDao.confirm(bookUrl, peerId)
                positionSync.request(SyncScope.Full)
            } else {
                identityDao.reject(bookUrl, peerId)
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
                    identityDao = container.database.workIdentityDao(),
                    bookDao = container.database.bookDao(),
                )
            }
        }
    }
}
