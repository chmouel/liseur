package com.chmouel.liseur.ui.widget

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a stream of refresh requests into few redraws.
 *
 * A redraw runs once requests have been quiet for [quietMs], or
 * [maxWaitMs] after the first request it serves, whichever comes first.
 * Reading writes a position on every page turn, so waiting for silence
 * alone would hold the widget back until the reader stopped; the ceiling
 * keeps it at most [maxWaitMs] behind. Requests that land while a redraw
 * runs are folded into exactly one more.
 */
class RefreshCoalescer(
    scope: CoroutineScope,
    private val quietMs: Long,
    private val maxWaitMs: Long,
    private val now: () -> Long,
    private val redraw: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { serve() }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private suspend fun serve() {
        while (true) {
            requests.receive()
            val first = now()
            while (true) {
                val left = maxWaitMs - (now() - first)
                if (left <= 0) break
                withTimeoutOrNull(minOf(quietMs, left)) { requests.receive() } ?: break
            }
            try {
                redraw()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Widget redraw failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "LiseurWidget"
    }
}
