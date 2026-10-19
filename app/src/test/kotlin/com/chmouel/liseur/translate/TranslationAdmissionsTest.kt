package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranslationAdmissionsTest {
    @Test
    fun `bulk claims admission before waiting for readers already submitted`(): Unit = runTest {
        val admissions = TranslationAdmissions()
        val settleReader = CompletableDeferred<Unit>()
        val releaseBulk = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.reader("book", "identity") {
                events += "reader submitted"
                settleReader.await()
                events += "reader saved"
            }
        }
        val bulk = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.bulk("book", "identity") {
                events += "bulk lookup"
                releaseBulk.await()
            }
        }
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.reader("book", "identity") { events += "reader recheck" }
        }
        runCurrent()
        assertEquals(listOf("reader submitted"), events)
        // Neither a different book nor a different identity is blocked.
        admissions.reader("other", "identity") { }
        admissions.reader("book", "other") { }
        settleReader.complete(Unit)
        first.join()
        runCurrent()
        assertEquals(listOf("reader submitted", "reader saved", "bulk lookup"), events)
        assertFalse(next.isCompleted)
        releaseBulk.complete(Unit)
        bulk.join()
        next.join()
        assertEquals("reader recheck", events.last())
    }

    @Test
    fun `cancelling a bulk owner settles workers before waking readers`(): Unit = runTest {
        val admissions = TranslationAdmissions()
        val worker = CompletableDeferred<Unit>()
        var settled = false
        val bulk = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.bulk("book", "identity") {
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            worker.await()
                        } finally {
                            settled = true
                        }
                    }
                    worker.await()
                }
            }
        }
        val cancelledReader = async(start = CoroutineStart.UNDISPATCHED) {
            admissions.reader("book", "identity") { error("Cancelled reader must not enter") }
        }
        cancelledReader.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            admissions.reader("book", "identity") { settled }
        }
        assertFalse(next.isCompleted)
        bulk.cancelAndJoin()
        assertTrue(next.await())
    }

    @Test
    fun `cancelling bulk while prior readers settle releases only its own claim`(): Unit = runTest {
        val admissions = TranslationAdmissions()
        val settle = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.reader("book", "identity") { settle.await() }
        }
        val bulk = launch(start = CoroutineStart.UNDISPATCHED) {
            admissions.bulk("book", "identity") { error("Reader has not settled") }
        }
        bulk.cancelAndJoin()
        admissions.reader("book", "identity") { }
        settle.complete(Unit)
        first.join()
        admissions.bulk("book", "identity") { }
    }
}
