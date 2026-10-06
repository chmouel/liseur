package com.chmouel.liseur.ui.launch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.FINISHED_PROGRESSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class LaunchResolution(
    val request: LaunchRequest,
    val book: Book? = null,
    val fileUrl: String? = null,
    val error: Int? = null,
)

class LaunchViewModel(
    private val savedState: SavedStateHandle,
    private val requests: LaunchRequests,
    private val flushReader: suspend () -> Boolean,
    private val continuation: suspend () -> Book?,
    private val openable: (Book) -> String? = { it.openableUri() },
    private val reportError: (Throwable) -> Unit,
) : ViewModel() {
    private val _ready = MutableStateFlow<LaunchResolution?>(null)
    val ready = _ready.asStateFlow()
    private val _resolving = MutableStateFlow(false)
    val resolving = _resolving.asStateFlow()

    init {
        LaunchTarget.fromAction(savedState[PENDING_ACTION])?.let(requests::restore)
        viewModelScope.launch {
            requests.pending.collectLatest { request ->
                _ready.value = null
                _resolving.value = request?.target == LaunchTarget.CONTINUE
                savedState[PENDING_ACTION] = request?.takeIf { it.shortcut }?.target?.action
                if (request == null) return@collectLatest
                val result = try {
                    if (request.target == LaunchTarget.CONTINUE) {
                        if (!flushReader()) {
                            LaunchResolution(request, error = R.string.reader_position_not_saved)
                        } else {
                            if (!requests.owns(request)) return@collectLatest
                            val book = continuation()
                            LaunchResolution(request, book, book?.let(openable))
                        }
                    } else {
                        LaunchResolution(request)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportError(e)
                    LaunchResolution(request, error = R.string.shortcut_open_failed)
                }
                if (requests.owns(request)) {
                    _ready.value = result
                    _resolving.value = false
                }
            }
        }
    }

    fun handled(request: LaunchRequest) {
        if (!requests.owns(request)) return
        savedState[PENDING_ACTION] = null
        requests.consume(request)
        _ready.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(LaunchViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return LaunchViewModel(
                savedState = extras.createSavedStateHandle(),
                requests = LaunchRequests.shared,
                flushReader = container.readingPositions::flushLastReader,
                continuation = { container.database.bookDao().continuationBook(FINISHED_PROGRESSION) },
                reportError = { android.util.Log.e("launcher-shortcut", "Could not open shortcut", it) },
            ) as T
        }
    }

    companion object {
        internal const val PENDING_ACTION = "shortcut_pending_action"
    }
}
