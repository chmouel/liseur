package com.chmouel.liseur.data.calibre

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDownloadLocksTest {
    @Test
    fun `catalog removal waits for a book worker to finish`() = runTest {
        val locks = BookDownloadLocks()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = async {
            locks.withBook("book-a") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val removal = async { locks.withBooks(listOf("book-b", "book-a")) { true } }
        runCurrent()
        assertFalse(removal.isCompleted)

        release.complete(Unit)
        assertTrue(removal.await())
        worker.await()
    }

    @Test
    fun `different books can download concurrently`() = runTest {
        val locks = BookDownloadLocks()
        val release = CompletableDeferred<Unit>()
        val worker = async { locks.withBook("book-a") { release.await() } }
        runCurrent()

        assertTrue(locks.withBook("book-b") { true })
        release.complete(Unit)
        worker.await()
    }
}
