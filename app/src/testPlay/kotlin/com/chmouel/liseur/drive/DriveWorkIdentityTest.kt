package com.chmouel.liseur.drive

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkAmbiguity
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveWorkIdentityTest {

    private lateinit var liseurDb: LiseurDatabase
    private lateinit var driveDb: DriveSyncDatabase
    private lateinit var identity: DriveWorkIdentity
    private var nowMs = 10_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        liseurDb = Room.inMemoryDatabaseBuilder(ctx, LiseurDatabase::class.java).build()
        driveDb = Room.inMemoryDatabaseBuilder(ctx, DriveSyncDatabase::class.java).build()
        identity = DriveWorkIdentity(
            database = liseurDb,
            store = driveDb,
            fingerprintStore = null,
            clock = { nowMs },
        )
    }

    @After
    fun tearDown() {
        driveDb.close()
        liseurDb.close()
    }

    private fun localBook(
        url: String,
        title: String,
        author: String? = "Author",
        workId: String? = null,
        fileMetadataChecked: Boolean = true,
    ) = Book(
        url = url,
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = 100L,
        lastOpenedAt = null,
        localUri = url,
        downloadState = DownloadState.DOWNLOADED,
        workId = workId,
        fileTitle = title,
        fileAuthor = author,
        fileMetadataChecked = fileMetadataChecked,
    )

    @Test
    fun `workKey derivation follows deterministic ISBN-13 over dc-identifier over edition sha256 priority`() = runTest {
        val perm = "perm-1"
        val sha1 = "1".repeat(64)
        val sha2 = "2".repeat(64)
        val sha3 = "3".repeat(64)

        // 1. ISBN-10 in file metadata normalizes to ISBN-13
        val isbnBook = localBook("file:///hobbit.epub", "The Hobbit", "Tolkien")
        liseurDb.bookDao().upsert(isbnBook)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(isbnBook.url, sha1, "md5-1", 1000L, null, 1L))
        liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(
                bookUrl = isbnBook.url,
                source = MetadataSource.FILE,
                snapshot = MetadataSnapshot(identifiers = listOf(Identifier("isbn", "0-261-10221-4"))),
            ),
        )
        val alias1 = identity.resolveBook(perm, isbnBook, computeFingerprintIfMissing = false)
        assertEquals("isbn:9780261102217", alias1?.workId)
        assertEquals("drive:$perm", alias1?.peerId)

        // 2. Usable dc:identifier UUID wins over SHA-256
        val dcBook = localBook("file:///dc.epub", "Custom Book", "Author", workId = "urn:uuid:550e8400-e29b-41d4-a716-446655440000")
        liseurDb.bookDao().upsert(dcBook)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(dcBook.url, sha2, "md5-2", 1000L, null, 1L))
        val alias2 = identity.resolveBook(perm, dcBook, computeFingerprintIfMissing = false)
        assertEquals(
            "dc:${DriveCanonicalJson.sha256Hex("urn:uuid:550e8400-e29b-41d4-a716-446655440000")}",
            alias2?.workId,
        )

        // 3. Useless identifier falls back to edition SHA-256
        val shaBook = localBook("file:///sha.epub", "No Id Book", "Author", workId = "unknown")
        liseurDb.bookDao().upsert(shaBook)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(shaBook.url, sha3, "md5-3", 1000L, null, 1L))
        val alias3 = identity.resolveBook(perm, shaBook, computeFingerprintIfMissing = false)
        assertEquals("sha:$sha3", alias3?.workId)

        // 4. Local book with fileMetadataChecked=false waits before minting a workKey
        val uncheckedBook = localBook("file:///unchecked.epub", "Unchecked", "Author", fileMetadataChecked = false)
        liseurDb.bookDao().upsert(uncheckedBook)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(uncheckedBook.url, "4".repeat(64), "md5-4", 1000L, null, 1L))
        assertNull(identity.resolveBook(perm, uncheckedBook, computeFingerprintIfMissing = false))
    }

    @Test
    fun `unrelated EPUBs sharing an erroneous ISBN keep separate work keys while editions of one work share it`() = runTest {
        val perm = "perm-1"
        val isbn = Identifier("isbn", "0-261-10221-4")
        suspend fun seed(url: String, title: String, author: String, sha: String): Book {
            val book = localBook(url, title, author)
            liseurDb.bookDao().upsert(book)
            liseurDb.workIdentityDao().upsert(BookFingerprintRow(url, sha, "md5-$sha", 1000L, null, 1L))
            liseurDb.bookMetadataDao().upsert(
                BookMetadata.of(
                    bookUrl = url,
                    source = MetadataSource.FILE,
                    snapshot = MetadataSnapshot(identifiers = listOf(isbn)),
                ),
            )
            return book
        }
        val hobbit = seed("file:///hobbit.epub", "The Hobbit", "Tolkien", "1".repeat(64))
        val other = seed("file:///other.epub", "Cooking Basics", "Someone Else", "2".repeat(64))
        val hobbitAgain = seed("file:///hobbit-2.epub", "The Hobbit", "Tolkien", "3".repeat(64))

        assertEquals("isbn:9780261102217", identity.resolveBook(perm, hobbit, computeFingerprintIfMissing = false)?.workId)
        assertEquals("sha:${"2".repeat(64)}", identity.resolveBook(perm, other, computeFingerprintIfMissing = false)?.workId)
        assertEquals("isbn:9780261102217", identity.resolveBook(perm, hobbitAgain, computeFingerprintIfMissing = false)?.workId)
    }

    @Test
    fun `rejecting a confirmed pair preserves unrelated candidates`() = runTest {
        val perm = "perm-1"
        val book = localBook("file:///confirmed.epub", "Emma", "Jane Austen")
        val peer = DriveLibrarySync.accountPeerId(perm)
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(WorkAlias(book.url, peer, "sha:aaa", WorkAlias.HIGH, confirmed = true, resolvedAt = 1L))
        identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:bbb", "Emma", "Jane Austen")
        identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:ccc", "Emma", "Jane Austen")
        identity.rejectMerge(perm, book.url, "sha:aaa", "isbn:bbb", "0017450000000-0001-dev-a")
        assertEquals(listOf("isbn:ccc"), identity.pendingMatches(perm).map { it.candidateWorkKey })
    }

    @Test
    fun `candidate rejection survives a remote work merge`() = runTest {
        val perm = "perm-1"
        val book = localBook("file:///choice-merge.epub", "Emma", "Jane Austen")
        liseurDb.bookDao().upsert(book)
        identity.detectAmbiguousTitleAuthorMatch(perm, "sha:bbb", "Emma", "Jane Austen")
        identity.rejectMerge(perm, book.url, "", "sha:bbb", "0017450000000-0001-dev-a")
        val merge = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:sha:bbb", "0017450000000-0002-dev-a",
            DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:bbb", "isbn:aaa", 1L)),
        )
        identity.applyRemoteRecord(perm, merge, "dev-a", 1L)
        assertFalse(identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:aaa", "Emma", "Jane Austen"))
        assertTrue(identity.pendingMatches(perm).isEmpty())
    }

    @Test
    fun `rejecting one unconfirmed candidate preserves the other`() = runTest {
        val perm = "perm-1"
        val book = localBook("file:///choice.epub", "Emma", "Jane Austen")
        liseurDb.bookDao().upsert(book)
        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:aaa", "Emma", "Jane Austen"))
        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:bbb", "Emma", "Jane Austen"))
        identity.rejectMerge(perm, book.url, "", "isbn:bbb", "0017450000000-0001-dev-a")
        assertEquals(listOf("isbn:aaa"), identity.pendingMatches(perm).map { it.candidateWorkKey })
        assertFalse(identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:bbb", "Emma", "Jane Austen"))
    }

    @Test
    fun `ambiguous title-author match requires explicit confirmation and publishes acyclic merge alias`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val localSha = "a".repeat(64)
        val bookA = localBook("file:///dune.epub", "Dune", "Frank Herbert")
        liseurDb.bookDao().upsert(bookA)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, localSha, "md5-a", 1000L, null, 1L))
        val localAlias = identity.resolveBook(perm, bookA, computeFingerprintIfMissing = false)!!
        assertEquals("sha:$localSha", localAlias.workId)

        // Remote catalog arrives with ISBN key and matching normalized title+author
        val remoteKey = "isbn:9780441172719"
        val detected = identity.detectAmbiguousTitleAuthorMatch(
            permissionId = perm,
            remoteWorkKey = remoteKey,
            remoteTitle = "Dune!",
            remoteAuthor = "Frank Herbert",
        )
        assertTrue(detected)
        // Must NOT have silently merged
        assertEquals("sha:$localSha", liseurDb.workIdentityDao().alias(bookA.url, peerId)?.workId)
        val pending = identity.pendingMatches(perm)
        assertEquals(1, pending.size)
        assertEquals(remoteKey, pending.single().candidateWorkKey)

        // Explicit confirmation enforces lexicographical to < from ("isbn:..." < "sha:...")
        val canonical = identity.confirmMerge(
            permissionId = perm,
            bookUrl = bookA.url,
            keyA = localAlias.workId,
            keyB = remoteKey,
            hlc = "0017450000000-0001-dev-a",
        )
        assertEquals(remoteKey, canonical)
        assertEquals(remoteKey, liseurDb.workIdentityDao().alias(bookA.url, peerId)?.workId)
        assertTrue(identity.pendingMatches(perm).isEmpty())

        // Outbound dirty merge record ready for delta publication
        val dirty = identity.collectDirtyRecords(perm)
        assertEquals(1, dirty.size)
        assertEquals("merge:sha:$localSha", dirty.single().k)
        val decoded = DriveWireCodec.decodeWorkAliasBody(dirty.single().body!!) as DriveWorkAliasBody.Merge
        assertEquals("sha:$localSha", decoded.from)
        assertEquals(remoteKey, decoded.to)
    }

    @Test
    fun `confirming one of several candidates for an unconfirmed book pins the alias without merging remote works`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val book = localBook("file:///hobbit.epub", "The Hobbit", "J. R. R. Tolkien")
        liseurDb.bookDao().upsert(book)
        val guessedCandidate = "sha:${"c".repeat(64)}"
        liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = book.url,
                peerId = peerId,
                workId = guessedCandidate,
                confidence = WorkAlias.LOW,
                confirmed = false,
                resolvedAt = 1L,
            ),
        )

        val remoteKey = "isbn:9780261102217"
        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, remoteKey, "The Hobbit", "J. R. R. Tolkien"))

        // Neither candidate is the book's confirmed identity, so no pair is offered as a merge.
        val pending = identity.pendingMatches(perm).filter { it.bookUrl == book.url }
        assertEquals(setOf(guessedCandidate, remoteKey), pending.map { it.candidateWorkKey }.toSet())
        assertTrue(pending.all { it.localWorkKey.isEmpty() })

        val canonical = identity.confirmMerge(
            permissionId = perm,
            bookUrl = book.url,
            keyA = pending.single { it.candidateWorkKey == remoteKey }.localWorkKey,
            keyB = remoteKey,
            hlc = "0017450000000-0001-dev-a",
        )

        assertEquals(remoteKey, canonical)
        val confirmed = requireNotNull(liseurDb.workIdentityDao().alias(book.url, peerId))
        assertEquals(remoteKey, confirmed.workId)
        assertTrue(confirmed.confirmed)
        assertEquals(WorkAlias.LOW, confirmed.confidence)
        // Choosing between two remote works for one book is no proof they are the same work.
        assertTrue(identity.collectDirtyRecords(perm).isEmpty())
        assertTrue(identity.pendingMatches(perm).isEmpty())
    }

    @Test
    fun `an incoming merge renames an unconfirmed alias without promoting it`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        liseurDb.workIdentityDao().upsert(
            WorkAlias("drive:sha:ccc", peerId, "sha:ccc", WorkAlias.LOW, confirmed = false, resolvedAt = 1L),
        )
        val merge = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = "0017450000000-0002-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "isbn:bbb", 100L)),
        )

        identity.applyRemoteRecord(perm, merge, "dev-b", 1L)

        val renamed = requireNotNull(liseurDb.workIdentityDao().alias("drive:sha:ccc", peerId))
        assertEquals("isbn:bbb", renamed.workId)
        assertEquals(WorkAlias.LOW, renamed.confidence)
        assertFalse(renamed.confirmed)
    }

    @Test
    fun `rejecting an ambiguous match suppresses future prompts and merge chains reach fixpoint`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val book = localBook("file:///classic.epub", "Pride and Prejudice", "Jane Austen")
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, "b".repeat(64), "md5-b", 1000L, null, 1L))
        val localAlias = identity.resolveBook(perm, book, computeFingerprintIfMissing = false)!!

        val otherKey = "isbn:9780141439518"
        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, otherKey, "Pride and Prejudice", "Jane Austen"))
        identity.rejectMerge(perm, book.url, localAlias.workId, otherKey, "0017450000000-0001-dev-a")

        assertTrue(identity.isRejectedPair(perm, localAlias.workId, otherKey))
        assertFalse(identity.detectAmbiguousTitleAuthorMatch(perm, otherKey, "Pride and Prejudice", "Jane Austen"))
        assertTrue(identity.pendingMatches(perm).isEmpty())

        // Verify transitive chain sha:ccc -> isbn:bbb -> dc:aaa resolves to dc:aaa without cycles
        val r1 = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = "0017450000000-0002-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "isbn:bbb", 100L)),
        )
        val r2 = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:isbn:bbb",
            hlc = "0017450000000-0003-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("isbn:bbb", "dc:aaa", 101L)),
        )
        liseurDb.workIdentityDao().upsert(
            WorkAlias("drive:sha:ccc", peerId, "sha:ccc", WorkAlias.HIGH, confirmed = true, resolvedAt = 1L),
        )
        identity.applyRemoteRecord(perm, r1, "dev-b", 1L)
        identity.applyRemoteRecord(perm, r2, "dev-b", 2L)

        assertEquals("dc:aaa", identity.canonicalWorkKey(perm, "sha:ccc"))
        assertEquals("dc:aaa", liseurDb.workIdentityDao().alias("drive:sha:ccc", peerId)?.workId)
        assertNotNull(driveDb.recordStates().get(perm, DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:isbn:bbb"))
    }

    @Test
    fun `confirming a merge promotes only the confirmed book and leaves other aliases of the losing key unchanged`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        dao.upsert(WorkAlias("file:///a.epub", peerId, "sha:ccc", WorkAlias.HIGH, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAlias("file:///guess.epub", peerId, "sha:ccc", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAlias("file:///no.epub", peerId, "sha:ccc", WorkAlias.REJECTED, confirmed = false, resolvedAt = 1L))

        val canonical = identity.confirmMerge(perm, "file:///a.epub", "sha:ccc", "isbn:bbb", "0017450000000-0001-dev-a")

        assertEquals("isbn:bbb", canonical)
        val confirmed = requireNotNull(dao.alias("file:///a.epub", peerId))
        assertEquals("isbn:bbb", confirmed.workId)
        assertEquals(WorkAlias.HIGH, confirmed.confidence)
        assertTrue(confirmed.confirmed)
        val guess = requireNotNull(dao.alias("file:///guess.epub", peerId))
        assertEquals("isbn:bbb", guess.workId)
        assertEquals(WorkAlias.LOW, guess.confidence)
        assertFalse(guess.confirmed)
        val rejected = requireNotNull(dao.alias("file:///no.epub", peerId))
        assertEquals("isbn:bbb", rejected.workId)
        assertEquals(WorkAlias.REJECTED, rejected.confidence)
        assertFalse(rejected.confirmed)
    }

    @Test
    fun `a user-confirmed low-confidence alias supplies the local key but a rejected one does not`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        val pinned = localBook("file:///pinned.epub", "The Hobbit", "J. R. R. Tolkien")
        val refused = localBook("file:///refused.epub", "The Hobbit", "J. R. R. Tolkien")
        liseurDb.bookDao().upsert(pinned)
        liseurDb.bookDao().upsert(refused)
        dao.upsert(WorkAlias(pinned.url, peerId, "sha:ccc", WorkAlias.LOW, confirmed = true, resolvedAt = 1L))
        dao.upsert(WorkAlias(refused.url, peerId, "sha:ddd", WorkAlias.REJECTED, confirmed = true, resolvedAt = 1L))

        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, "isbn:bbb", "The Hobbit", "J. R. R. Tolkien"))

        val pending = identity.pendingMatches(perm)
        val pinnedMatch = pending.single { it.bookUrl == pinned.url && it.candidateWorkKey == "isbn:bbb" }
        assertEquals("sha:ccc", pinnedMatch.localWorkKey)
        assertTrue(pending.none { it.bookUrl == refused.url })
    }

    @Test
    fun `a rejected alias is never resolved again`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        val book = localBook("file:///refused.epub", "The Hobbit", "J. R. R. Tolkien")
        liseurDb.bookDao().upsert(book)
        dao.upsert(BookFingerprintRow(book.url, "5".repeat(64), "md5-5", 1000L, null, 1L))
        dao.upsert(WorkAlias(book.url, peerId, "sha:ddd", WorkAlias.REJECTED, confirmed = false, resolvedAt = 1L))

        assertNull(identity.resolveBook(perm, book, computeFingerprintIfMissing = false))

        val kept = dao.alias(book.url, peerId)!!
        assertEquals(WorkAlias.REJECTED, kept.confidence)
        assertEquals("sha:ddd", kept.workId)
    }

    @Test
    fun `a resolution refused for a relinked book is neither returned nor stored`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        liseurDb.bookDao().upsert(localBook("file:///relinked.epub", "Relinked"))
        val stored = liseurDb.bookDao().getByUrl("file:///relinked.epub")!!
        dao.upsert(BookFingerprintRow(stored.url, "6".repeat(64), "md5-6", 1000L, null, 1L))
        val stale = stored.copy(linkGeneration = stored.linkGeneration + 1)

        assertNull(identity.resolveBook(perm, stale, computeFingerprintIfMissing = false))
        assertNull(dao.alias(stored.url, peerId))

        assertEquals("sha:${"6".repeat(64)}", identity.resolveBook(perm, stored, computeFingerprintIfMissing = false)?.workId)
    }

    @Test
    fun `a candidate offered twice for one unconfirmed book is listed once`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        val book = localBook("file:///dune.epub", "Dune", "Frank Herbert")
        liseurDb.bookDao().upsert(book)
        dao.upsert(WorkAlias(book.url, peerId, "isbn:aaa", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAmbiguity(book.url, peerId, "isbn:aaa\nisbn:bbb", 1L))

        val pending = identity.pendingMatches(perm)

        assertEquals(listOf("isbn:aaa", "isbn:bbb"), pending.map { it.candidateWorkKey })
    }

    @Test
    fun `a placeholder identifier under a scheme falls back to the edition sha`() = runTest {
        val perm = "perm-1"
        val sha = "7".repeat(64)
        val book = localBook("file:///placeholder.epub", "Placeholder")
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, sha, "md5-7", 1000L, null, 1L))
        liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(
                bookUrl = book.url,
                source = MetadataSource.FILE,
                snapshot = MetadataSnapshot(identifiers = listOf(Identifier("isbn", "unknown"))),
            ),
        )

        assertEquals("sha:$sha", identity.resolveBook(perm, book, computeFingerprintIfMissing = false)?.workId)
    }

    @Test
    fun `a rejection still suppresses the pair after either endpoint is merged into another key`() = runTest {
        val perm = "perm-1"
        identity.rejectMerge(perm, "file:///x.epub", "isbn:bbb", "sha:ccc", "0017450000000-0001-dev-a")
        assertTrue(identity.isRejectedPair(perm, "isbn:bbb", "sha:ccc"))

        val merge = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:isbn:bbb",
            hlc = "0017450000000-0002-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("isbn:bbb", "dc:aaa", 100L)),
        )
        identity.applyRemoteRecord(perm, merge, "dev-b", 1L)

        assertTrue(identity.isRejectedPair(perm, "dc:aaa", "sha:ccc"))
        assertFalse(identity.isRejectedPair(perm, "dc:aaa", "sha:other"))
        // A merge that makes the endpoints equal supersedes the rejection.
        val joined = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = "0017450000000-0003-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "dc:aaa", 101L)),
        )
        identity.applyRemoteRecord(perm, joined, "dev-b", 2L)
        assertFalse(identity.isRejectedPair(perm, "dc:aaa", "sha:ccc"))
    }

    @Test
    fun `concurrent merges of one source to different targets also link the targets`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        liseurDb.workIdentityDao().upsert(
            WorkAlias("drive:isbn:bbb", peerId, "isbn:bbb", WorkAlias.HIGH, confirmed = true, resolvedAt = 1L),
        )
        val toB = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = "0017450000000-0002-dev-b",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "isbn:bbb", 100L)),
        )
        val toA = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = "0017450000000-0003-dev-c",
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "dc:aaa", 101L)),
        )
        identity.applyRemoteRecord(perm, toB, "dev-b", 1L) { "0017450000001-0000-dev-a" }
        identity.applyRemoteRecord(perm, toA, "dev-c", 1L) { "0017450000002-0000-dev-a" }

        assertEquals("dc:aaa", identity.canonicalWorkKey(perm, "sha:ccc"))
        assertEquals("dc:aaa", identity.canonicalWorkKey(perm, "isbn:bbb"))
        assertEquals("dc:aaa", liseurDb.workIdentityDao().alias("drive:isbn:bbb", peerId)?.workId)
        val derived = requireNotNull(driveDb.recordStates().get(perm, DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:isbn:bbb"))
        assertNull(derived.publishedFp)
        assertEquals(listOf("merge:isbn:bbb"), identity.collectDirtyRecords(perm).map { it.k })
    }

    @Test
    fun `a losing concurrent merge leaves the stored winner body and fingerprint intact`() = runTest {
        val perm = "perm-1"
        fun merge(to: String, hlc: String, updatedAt: Long) = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = hlc,
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", to, updatedAt)),
        )
        val toA = merge("dc:aaa", "0017450000000-0002-dev-b", 100L)
        val toB = merge("isbn:bbb", "0017450000000-0003-dev-c", 101L)
        identity.applyRemoteRecord(perm, toA, "dev-b", 1L) { "0017450000001-0000-dev-a" }
        identity.applyRemoteRecord(perm, toB, "dev-c", 1L) { "0017450000002-0000-dev-a" }

        val state = requireNotNull(driveDb.recordStates().get(perm, DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:sha:ccc"))
        assertEquals(toA.fp, state.versionFp)
        assertEquals(toA.fp, state.publishedFp)
        assertEquals("dev-b", state.originDeviceId)
        assertEquals(DriveCanonicalJson.contentFp(org.json.JSONObject(state.bodyJson!!)), state.contentFp)
        assertEquals(DriveCanonicalJson.putFp(org.json.JSONObject(state.bodyJson!!), state.lastHlc), state.versionFp)
        assertEquals(toA.body.toString(), state.bodyJson)
        assertEquals("dc:aaa", identity.canonicalWorkKey(perm, "isbn:bbb"))
    }

    @Test
    fun `a concurrent merge never replaces an existing target edge`() = runTest {
        val perm = "perm-1"
        fun merge(from: String, to: String, hlc: String) = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:$from",
            hlc = hlc,
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(from, to, 100L)),
        )
        // dc:aaa < dc:bbb < isbn:mmm < sha:zzz
        identity.applyRemoteRecord(perm, merge("sha:zzz", "isbn:mmm", "0017450000000-0001-dev-b"), "dev-b", 1L)
        identity.applyRemoteRecord(perm, merge("isbn:mmm", "dc:aaa", "0017450000000-0002-dev-b"), "dev-b", 2L)
        identity.applyRemoteRecord(perm, merge("sha:zzz", "dc:bbb", "0017450000000-0003-dev-c"), "dev-c", 1L) {
            "0017450000001-0000-dev-a"
        }

        for (key in listOf("sha:zzz", "isbn:mmm", "dc:bbb")) {
            assertEquals(key, "dc:aaa", identity.canonicalWorkKey(perm, key))
        }
        val mmm = requireNotNull(driveDb.recordStates().get(perm, DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:isbn:mmm"))
        assertTrue(mmm.bodyJson!!.contains("dc:aaa"))
        assertEquals(listOf("merge:dc:bbb"), identity.collectDirtyRecords(perm).map { it.k })
    }

    private fun failWorkAliasWrites() {
        val db = liseurDb.openHelper.writableDatabase
        for (op in listOf("INSERT", "UPDATE", "DELETE")) {
            db.execSQL("CREATE TRIGGER crash_$op BEFORE $op ON work_alias BEGIN SELECT RAISE(ABORT, 'crash'); END")
        }
    }

    private fun allowWorkAliasWrites() {
        val db = liseurDb.openHelper.writableDatabase
        for (op in listOf("INSERT", "UPDATE", "DELETE")) db.execSQL("DROP TRIGGER crash_$op")
    }

    private fun freshIdentity() = DriveWorkIdentity(liseurDb, driveDb, null) { nowMs }

    private suspend fun pendingMarkers(perm: String) =
        driveDb.recordStates().forDomain(perm, DriveWorkIdentity.DOMAIN_PENDING_ALIAS_EFFECT)

    @Test
    fun `a confirmed merge survives a crash before local aliases change and replays on a fresh instance`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        val otherPeer = DriveLibrarySync.accountPeerId("perm-2")
        dao.upsert(WorkAlias("file:///a.epub", peerId, "sha:ccc", WorkAlias.HIGH, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAlias("file:///guess.epub", peerId, "sha:ccc", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAlias("file:///a.epub", otherPeer, "sha:ccc", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAmbiguity("file:///a.epub", peerId, "sha:ccc\nisbn:bbb", 1L))

        failWorkAliasWrites()
        try {
            identity.confirmMerge(perm, "file:///a.epub", "sha:ccc", "isbn:bbb", "0017450000000-0001-dev-a")
            org.junit.Assert.fail("expected the local write to fail")
        } catch (_: Exception) {
        }
        allowWorkAliasWrites()

        assertEquals(listOf("merge:sha:ccc"), identity.collectDirtyRecords(perm).map { it.k })
        assertEquals(1, pendingMarkers(perm).size)
        assertEquals("sha:ccc", dao.alias("file:///a.epub", peerId)?.workId)

        val fresh = freshIdentity()
        assertTrue(fresh.pendingMatches(perm).isEmpty())

        val confirmed = requireNotNull(dao.alias("file:///a.epub", peerId))
        assertEquals("isbn:bbb", confirmed.workId)
        assertEquals(WorkAlias.HIGH, confirmed.confidence)
        assertTrue(confirmed.confirmed)
        val guess = requireNotNull(dao.alias("file:///guess.epub", peerId))
        assertEquals("isbn:bbb", guess.workId)
        assertEquals(WorkAlias.LOW, guess.confidence)
        assertFalse(guess.confirmed)
        val other = requireNotNull(dao.alias("file:///a.epub", otherPeer))
        assertEquals("sha:ccc", other.workId)
        assertEquals(WorkAlias.LOW, other.confidence)
        assertTrue(dao.ambiguitiesFor(peerId).isEmpty())
        assertTrue(pendingMarkers(perm).isEmpty())
        assertEquals(listOf("merge:sha:ccc"), fresh.collectDirtyRecords(perm).map { it.k })

        // A second replay changes nothing.
        fresh.replayPendingAliasEffects(perm)
        assertEquals(confirmed, dao.alias("file:///a.epub", peerId))
    }

    @Test
    fun `a pair rejection survives a crash before the local effects and no prompt returns after replay`() = runTest {
        val perm = "perm-1"
        val peerId = DriveLibrarySync.accountPeerId(perm)
        val dao = liseurDb.workIdentityDao()
        val book = localBook("file:///classic.epub", "Pride and Prejudice", "Jane Austen")
        liseurDb.bookDao().upsert(book)
        val remoteKey = "isbn:9780141439518"
        assertTrue(identity.detectAmbiguousTitleAuthorMatch(perm, remoteKey, "Pride and Prejudice", "Jane Austen"))
        assertTrue(requireNotNull(dao.alias(book.url, peerId)).awaitingAnswer)

        failWorkAliasWrites()
        try {
            identity.rejectMerge(perm, book.url, "", remoteKey, "0017450000000-0001-dev-a")
            org.junit.Assert.fail("expected the local write to fail")
        } catch (_: Exception) {
        }
        allowWorkAliasWrites()
        // A reject with a blank endpoint has no wire record to protect, so a failed attempt is simply retried.
        assertTrue(pendingMarkers(perm).isEmpty())
        assertTrue(requireNotNull(dao.alias(book.url, peerId)).awaitingAnswer)
        assertTrue(identity.collectDirtyRecords(perm).isEmpty())
        identity.rejectMerge(perm, book.url, "", remoteKey, "0017450000000-0003-dev-a")
        assertEquals(WorkAlias.REJECTED, dao.alias(book.url, peerId)?.confidence)

        val pairBook = localBook("file:///pair.epub", "Emma", "Jane Austen")
        liseurDb.bookDao().upsert(pairBook)
        dao.upsert(WorkAlias(pairBook.url, peerId, "sha:aaa", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAmbiguity(pairBook.url, peerId, "sha:aaa\nisbn:emma", 1L))
        failWorkAliasWrites()
        try {
            identity.rejectMerge(perm, pairBook.url, "sha:aaa", "isbn:emma", "0017450000000-0002-dev-a")
        } catch (_: Exception) {
        }
        allowWorkAliasWrites()
        assertEquals(listOf("reject:isbn:emma|sha:aaa"), identity.collectDirtyRecords(perm).map { it.k })
        assertEquals(1, dao.ambiguitiesFor(peerId).size)

        val fresh = freshIdentity()
        assertFalse(fresh.detectAmbiguousTitleAuthorMatch(perm, remoteKey, "Pride and Prejudice", "Jane Austen"))
        assertTrue(fresh.pendingMatches(perm).isEmpty())
        assertTrue(dao.ambiguitiesFor(peerId).isEmpty())
        assertTrue(pendingMarkers(perm).isEmpty())
        assertEquals(listOf("reject:isbn:emma|sha:aaa"), fresh.collectDirtyRecords(perm).map { it.k })
        assertEquals(WorkAlias.REJECTED, dao.alias(pairBook.url, peerId)?.confidence)
    }

    @Test
    fun `replaying one account's pending effects never touches another account`() = runTest {
        val perm = "perm-1"
        val otherPerm = "perm-2"
        val dao = liseurDb.workIdentityDao()
        val otherPeer = DriveLibrarySync.accountPeerId(otherPerm)
        dao.upsert(WorkAlias("file:///a.epub", DriveLibrarySync.accountPeerId(perm), "sha:ccc", WorkAlias.HIGH, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAlias("file:///a.epub", otherPeer, "sha:ccc", WorkAlias.LOW, confirmed = false, resolvedAt = 1L))
        dao.upsert(WorkAmbiguity("file:///a.epub", otherPeer, "sha:ccc\nisbn:bbb", 1L))
        failWorkAliasWrites()
        try {
            identity.confirmMerge(perm, "file:///a.epub", "sha:ccc", "isbn:bbb", "0017450000000-0001-dev-a")
        } catch (_: Exception) {
        }
        allowWorkAliasWrites()

        freshIdentity().replayPendingAliasEffects(otherPerm)
        assertEquals(1, pendingMarkers(perm).size)
        assertEquals("sha:ccc", dao.alias("file:///a.epub", DriveLibrarySync.accountPeerId(perm))?.workId)

        freshIdentity().replayPendingAliasEffects(perm)
        val other = requireNotNull(dao.alias("file:///a.epub", otherPeer))
        assertEquals("sha:ccc", other.workId)
        assertEquals(WorkAlias.LOW, other.confidence)
        assertEquals(1, dao.ambiguitiesFor(otherPeer).size)
    }
}
