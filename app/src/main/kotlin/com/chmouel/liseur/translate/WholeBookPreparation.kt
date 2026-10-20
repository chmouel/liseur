package com.chmouel.liseur.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class PreparationStage { OPENING, VALIDATING, SCANNING, FINISHING }

internal data class PreparationProgress(
    val stage: PreparationStage = PreparationStage.OPENING,
    val section: Int = 0,
    val sections: Int = 0,
    val sentences: Int = 0,
) {
    val fraction: Float?
        get() = when {
            sections <= 0 -> null
            stage == PreparationStage.VALIDATING -> section.toFloat() / sections
            stage == PreparationStage.SCANNING -> (section - 1).coerceAtLeast(0).toFloat() / sections
            else -> null
        }
}

internal sealed interface PreparationState {
    data object Idle : PreparationState
    data class Checking(val progress: PreparationProgress = PreparationProgress()) : PreparationState
    data class Ready(val preview: WholeBookPreview) : PreparationState
    data class Failed(val error: Exception, val retryFailures: Boolean) : PreparationState
}

/** One book's local check, owned by the application rather than the page observing it. */
internal class WholeBookPreparation(
    private val scope: CoroutineScope,
    private val check: suspend (Boolean, (PreparationProgress) -> Unit) -> WholeBookPreview,
    private val valid: suspend (WholeBookPreview) -> Boolean,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow<PreparationState>(PreparationState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request = 0L
    private var retrying = false

    fun prepare(retryFailures: Boolean = false) = synchronized(lock) {
        if (job?.isActive == true && retrying == retryFailures) return@synchronized
        cancel()
        retrying = retryFailures
        launch(PreparationState.Checking()) { token ->
            val preview = check(retryFailures) { progress ->
                publish(token, PreparationState.Checking(progress))
            }
            PreparationState.Ready(preview)
        }
    }

    fun validate() = synchronized(lock) {
        if (job?.isActive == true) return@synchronized
        val ready = mutable.value as? PreparationState.Ready ?: return@synchronized
        launch(PreparationState.Checking()) {
            if (valid(ready.preview)) ready else PreparationState.Idle
        }
    }

    fun cancel() = synchronized(lock) {
        request++
        job?.cancel()
        job = null
        mutable.value = PreparationState.Idle
    }

    private fun publish(token: Long, state: PreparationState) = synchronized(lock) {
        if (request == token) mutable.value = state
    }

    private fun launch(initial: PreparationState, work: suspend (Long) -> PreparationState) {
        val token = ++request
        mutable.value = initial
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                publish(token, work(token))
            } catch (e: CancellationException) {
                publish(token, PreparationState.Idle)
                throw e
            } catch (e: Exception) {
                publish(token, PreparationState.Failed(e, retrying))
            }
        }.also { it.start() }
    }
}
