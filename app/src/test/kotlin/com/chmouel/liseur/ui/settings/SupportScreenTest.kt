package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.SupportReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportScreenTest {

    private fun book(
        url: String,
        title: String = url,
        addedAt: Long = 100L,
        lastOpenedAt: Long? = null,
        finishedAt: Long? = null,
        archivedAt: Long? = null,
        hiddenAt: Long? = null,
        downloadState: DownloadState = DownloadState.DOWNLOADED,
    ) = Book(
        url = url,
        title = title,
        author = null,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        finishedAt = finishedAt,
        archivedAt = archivedAt,
        hiddenAt = hiddenAt,
        downloadState = downloadState,
    )

    @Test
    fun `ledger puts read books first, fills remaining lines from local shelf, and skips remote or hidden books`() {
        val books = listOf(
            book("read-a", title = "Piranesi", addedAt = 10L),
            book("read-b", title = "Germinal", addedAt = 20L),
            book("shelved-new", title = "Dune", addedAt = 300L),
            book("shelved-opened", title = "Solaris", addedAt = 50L, lastOpenedAt = 500L),
            book("remote-unopened", title = "Catalog Only", addedAt = 900L, downloadState = DownloadState.REMOTE),
            book("hidden", title = "Secret", addedAt = 800L, hiddenAt = 850L),
            book("archived", title = "Old", addedAt = 700L, archivedAt = 750L),
        )
        val reading = listOf(
            SupportReading("read-b", durationMs = 120_000L, lastReadAt = 400L),
            SupportReading("hidden", durationMs = 999_000L, lastReadAt = 999L),
            SupportReading("read-a", durationMs = 60_000L, lastReadAt = 250L),
        )

        val ledger = buildSupportLedger(books, reading, maxRows = 4)

        assertEquals(4, ledger.bookCount)
        assertEquals(180_000L, ledger.totalDurationMs)
        assertEquals(
            listOf(
                SupportLedgerRow("read-b", "Germinal", 400L, 120_000L),
                SupportLedgerRow("read-a", "Piranesi", 250L, 60_000L),
                SupportLedgerRow("shelved-opened", "Solaris", 500L, 0L),
                SupportLedgerRow("shelved-new", "Dune", 300L, 0L),
            ),
            ledger.rows,
        )
    }

    @Test
    fun `empty shelf or remote-only catalog yields an empty ledger`() {
        val ledger = buildSupportLedger(
            books = listOf(book("remote", downloadState = DownloadState.REMOTE)),
            reading = emptyList(),
        )
        assertEquals(0, ledger.bookCount)
        assertEquals(0L, ledger.totalDurationMs)
        assertTrue(ledger.rows.isEmpty())
    }
}
