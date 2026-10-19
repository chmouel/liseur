package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transfers a book/configuration from live readers to bulk without overlapping paid work. */
internal class TranslationAdmissions {
    private data class Key(val book: String, val identity: String)
    private class State {
        var readers = 0
        var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
        var bulk: CompletableDeferred<Unit>? = null
    }
    private val states = HashMap<Key, State>()

    suspend fun <T> reader(book: String, identity: String, work: suspend () -> T): T {
        val key = Key(book, identity)
        while (true) {
            currentCoroutineContext().ensureActive()
            val (state, wait) = synchronized(states) {
                val state = states.getOrPut(key, ::State)
                val wait = state.bulk
                if (wait == null && state.readers++ == 0) state.drained = CompletableDeferred()
                state to wait
            }
            if (wait != null) {
                wait.await()
                continue
            }
            try {
                return work()
            } finally {
                synchronized(states) {
                    if (--state.readers == 0) {
                        state.drained.complete(Unit)
                        if (state.bulk == null) states.remove(key)
                    }
                }
            }
        }
    }

    suspend fun <T> bulk(book: String, identity: String, work: suspend () -> T): T {
        val key = Key(book, identity)
        currentCoroutineContext().ensureActive()
        val owner = CompletableDeferred<Unit>()
        val state = synchronized(states) {
            states.getOrPut(key, ::State).also {
                check(it.bulk == null) { "Translation already owns this book" }
                it.bulk = owner
            }
        }
        try {
            state.drained.await()
            return work()
        } finally {
            synchronized(states) {
                state.bulk = null
                owner.complete(Unit)
                if (state.readers == 0) states.remove(key)
            }
        }
    }
}
