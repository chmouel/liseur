package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.BookUploader
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.settings.UploadPolicy
import com.chmouel.liseur.sync.LibraryUploadTarget
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which destination a locally added book is offered to, and which books a
 * backend-provided target leaves out.
 *
 * Only one destination is active at a time (ADR-0078), so these rules decide
 * whether the reader is asked at all when a server is not the active sync
 * source.
 */
class UploadDestinationTest {

    @Test
    fun `a server wins over a library-sync target`() {
        assertEquals(
            UploadDestination.Server(server().accountKey),
            uploadDestination(server(canUpload = true), router(), target()),
        )
    }

    /** A server that cannot take uploads must not fall through to Drive. */
    @Test
    fun `a server that cannot upload is not replaced by the target`() {
        assertNull(uploadDestination(server(canUpload = false), router(), target()))
    }

    @Test
    fun `with no server the target is the destination`() {
        assertEquals(UploadDestination.LibrarySync(target()), uploadDestination(null, router(), target()))
    }

    @Test
    fun `with neither there is no destination`() {
        assertNull(uploadDestination(null, router(), null))
    }

    @Test
    fun `notOffered covers queued, held, owned and pre-cutoff books`() {
        val queued = local("file:///sd/a.epub", addedAt = 1)
        val held = local("file:///sd/b.epub", addedAt = 1)
        val owned = local("drive:3", addedAt = 1)
        val before = local("file:///sd/c.epub", addedAt = 4)
        val atCutoff = local("file:///sd/d.epub", addedAt = 5)
        val after = local("file:///sd/e.epub", addedAt = 6)
        val books = listOf(queued, held, owned, before, atCutoff, after)

        val skipped =
            target(
                offerSince = 5,
                queued = setOf(queued.url),
                held = setOf(held.url),
            ).notOffered(books) { it.url == owned.url }

        assertEquals(setOf(queued.url, held.url, owned.url, before.url), skipped)
    }

    @Test
    fun `notOffered covers withheld books and offers nothing before the selection is settled`() {
        val withheld = local("file:///sd/a.epub", addedAt = 6)
        val books = listOf(withheld, local("file:///sd/b.epub", addedAt = 6))

        assertEquals(
            setOf(withheld.url),
            target(offerSince = 5, withheld = setOf(withheld.url)).notOffered(books) { false },
        )
        assertEquals(
            books.map { it.url }.toSet(),
            target(offerSince = 5, selectionSettled = false).notOffered(books) { false },
        )
    }

    /** Withholding only silences the automatic offer; asking by hand is still consent. */
    @Test
    fun `alreadyTaken does not hide a withheld book`() {
        val withheld = local("file:///sd/a.epub", addedAt = 6)

        val taken = target(offerSince = 5, withheld = setOf(withheld.url))
            .alreadyTaken(listOf(withheld)) { false }

        assertTrue(taken.isEmpty())
    }

    @Test
    fun `alreadyTaken ignores the cutoff`() {
        val queued = local("file:///sd/a.epub", addedAt = 1)
        val before = local("file:///sd/b.epub", addedAt = 1)
        val books = listOf(queued, before)

        val taken = target(offerSince = 5, queued = setOf(queued.url)).alreadyTaken(books) { false }

        assertEquals(setOf(queued.url), taken)
    }

    @Test
    fun `ask always and never reach their verdict through the destination`() {
        val book = local("file:///sd/a.epub")
        val destination = UploadDestination.LibrarySync(target())

        assertEquals(UploadDecision.ASK, decide(book, UploadPolicy.ASK, destination))
        assertEquals(UploadDecision.SEND, decide(book, UploadPolicy.ALWAYS, destination))
        assertEquals(UploadDecision.NOTHING, decide(book, UploadPolicy.NEVER, destination))
    }

    @Test
    fun `drive notOffered books are never asked or auto-sent`() {
        val book = local("file:///sd/a.epub")
        val refused = target(queued = setOf(book.url)).notOffered(listOf(book)) { false }

        assertEquals(UploadDecision.NOTHING, decide(book, UploadPolicy.ASK, UploadDestination.LibrarySync(target()), refused))
        assertEquals(
            UploadDecision.NOTHING,
            decide(book, UploadPolicy.ALWAYS, UploadDestination.LibrarySync(target()), refused),
        )
        assertEquals(UploadDecision.NOTHING, decide(book, UploadPolicy.NEVER, UploadDestination.LibrarySync(target()), refused))
    }

    @Test
    fun `the bulk rules agree with a library-sync destination`() {
        val books = listOf(local("file:///sd/a.epub"), local("file:///sd/b.epub"))
        val target = target()
        assertEquals(books, booksToSendUp(books, UploadPolicy.ALWAYS, canUpload = true))
        assertEquals(books, books.awaitingUpload(canUpload = true))
        assertTrue(target.alreadyTaken(books) { false }.isEmpty())
    }

    private fun decide(
        book: Book,
        policy: UploadPolicy,
        destination: UploadDestination?,
        refused: Set<String> = emptySet(),
    ): UploadDecision =
        uploadOnOpen(
            book = book,
            policy = policy,
            canUpload = destination != null,
            alreadyAnswered = false,
            refused = refused,
        )

    private fun target(
        offerSince: Long = 0,
        selectionSettled: Boolean = true,
        queued: Set<String> = emptySet(),
        held: Set<String> = emptySet(),
        withheld: Set<String> = emptySet(),
    ) = LibraryUploadTarget(
        accountKey = "drive:1",
        selectionSettled = selectionSettled,
        offerSince = offerSince,
        queued = queued,
        held = held,
        withheld = withheld,
    )

    private fun router(): RemoteRouter {
        val dao = Proxy.newProxyInstance(
            RemoteServerDao::class.java.classLoader, arrayOf(RemoteServerDao::class.java),
        ) { _, _, _ -> error("not asked") } as RemoteServerDao
        val uploader = Proxy.newProxyInstance(
            BookUploader::class.java.classLoader, arrayOf(BookUploader::class.java),
        ) { _, _, _ -> error("not asked") } as BookUploader
        return RemoteRouter(
            dao,
            emptyMap(),
            emptyMap(),
            emptyMap(),
            uploaders = mapOf(ServerKind.LISEUR_SYNC to uploader),
        )
    }

    private fun server(canUpload: Boolean = true): RemoteServer = RemoteServer(
        kind = ServerKind.LISEUR_SYNC,
        baseUrl = "https://example.invalid",
        username = null,
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        canDelete = false,
        canUpload = canUpload,
        addedAt = 0,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )

    private fun local(url: String = "file:///sd/a.epub", addedAt: Long = 0) = Book(
        url = url,
        title = "A Book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = null,
    )
}
