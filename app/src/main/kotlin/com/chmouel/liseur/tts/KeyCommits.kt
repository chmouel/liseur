package com.chmouel.liseur.tts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saves and removals of API keys, run in the service's own [scope] so
 * one submitted as the screen closes still completes. They run in the
 * order they were submitted: each takes the lock before it first waits,
 * and the lock serves waiters in turn, so the last one submitted wins.
 */
internal class KeyCommits(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val failed = MutableStateFlow<String?>(null)

    /** The server whose last key save or removal failed, if any; never the key itself. */
    val failure: StateFlow<String?> = failed.asStateFlow()

    /** Runs [op] for [owner]'s key after every one submitted before it, then tells [done] whether it worked. */
    fun submit(owner: String, op: suspend () -> Unit, done: (Boolean) -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ok = lock.withLock {
                try {
                    op()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                failed.compareAndSet(owner, null)
            } else {
                failed.value = owner
            }
            done(ok)
        }
    }
}

/**
 * The key being typed in a key field, never kept beyond it. It belongs to
 * the server shown when its first character was typed, and is saved
 * there, whichever server is shown by then. Each edit is a new revision,
 * and one revision is submitted once, however many ways the field is
 * left (Done, focus moving away, the screen closing).
 */
@Stable
internal class KeyDraft {
    var text by mutableStateOf("")
        private set

    private var owner: String? = null
    private var revision = 0
    private var submitted = -1

    fun edit(value: String, shown: String?) {
        if (text.isEmpty() && value.isNotEmpty()) owner = shown
        if (value.isEmpty()) owner = null
        text = value
        revision++
    }

    /**
     * Submits the draft to its own server, once per revision. It leaves
     * the field once saved, unless edited since; a failed save keeps it to
     * be submitted again.
     */
    fun commit(save: (owner: String, key: String, done: (Boolean) -> Unit) -> Unit) {
        val key = text.trim()
        val to = owner ?: return
        if (key.isEmpty() || submitted == revision) return
        val at = revision
        submitted = at
        save(to, key) { ok ->
            if (revision != at) return@save
            if (ok) clear() else submitted = -1
        }
    }

    /** The field now shows [shown]'s key: a draft for another server is saved there and leaves the field. */
    fun follow(shown: String?, save: (owner: String, key: String, done: (Boolean) -> Unit) -> Unit) {
        if (owner == null || owner == shown) return
        commit(save)
        clear()
    }

    /** Drops the draft unsaved, as when the key is removed. */
    fun clear() {
        text = ""
        owner = null
        revision++
    }
}
