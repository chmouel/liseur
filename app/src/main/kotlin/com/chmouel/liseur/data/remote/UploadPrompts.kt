package com.chmouel.liseur.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The books already asked about, so nothing is asked about twice.
 *
 * A book can raise the question in two places: the reader, when it was
 * just opened from outside, and the shelf, which offers everything the
 * server has not got. Without somewhere shared to write the answer down
 * they contradict each other within seconds — decline in the reader,
 * press back, and the shelf asks again about the same book.
 *
 * *Send* is recorded as well as *not now*. An upload becomes visible
 * through [BookUploadRepository.inFlight], which reads WorkManager, and
 * there is a window after `enqueue()` where the work is not reported
 * yet; the shelf's offer would fire inside it.
 *
 * Nothing here is written to disk, and that is the feature: the answer
 * lasts as long as the process, so a refusal is not forever. The next
 * time the app starts, a book that is still only on this device is
 * worth asking about again.
 */
class UploadPrompts {

    private val _answered = MutableStateFlow<Set<String>>(emptySet())

    /** Book URLs that have had their answer this run. */
    val answered: StateFlow<Set<String>> = _answered.asStateFlow()

    /** Records an answer, whichever way it went. */
    fun answer(bookUrl: String) {
        _answered.update { it + bookUrl }
    }

    fun wasAnswered(bookUrl: String): Boolean = bookUrl in _answered.value
}
