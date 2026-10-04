package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.opds.StarterCatalog
import com.chmouel.liseur.data.remote.BrowseCategory
import com.chmouel.liseur.data.remote.BrowseListing
import com.chmouel.liseur.data.remote.BrowseOwned
import com.chmouel.liseur.data.remote.BrowseOwnedState
import com.chmouel.liseur.data.remote.BrowseWalk
import com.chmouel.liseur.data.remote.BrowseWalkCache
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.browseOwned
import com.chmouel.liseur.data.remote.browseCoverUrl
import com.chmouel.liseur.data.remote.browseCoverRequestHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseLibrariesTest {

    private fun remote(id: String, href: String? = "/get/$id", size: Long? = 100L) =
        RemoteBook(remoteId = id, title = id, author = null, coverHref = null, downloadHref = href, sizeBytes = size)

    private fun book(state: DownloadState, localUri: String? = null) = Book(
        url = "browse:1:calibre:a",
        title = "a",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = null,
        localUri = localUri,
        downloadState = state,
    )

    private fun server(canDownload: Boolean = true) = RemoteServer(
        id = 2L,
        kind = ServerKind.CALIBRE,
        baseUrl = "https://books.example.com",
        username = "me",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = canDownload,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )

    @Test
    fun `a running download wins over the stored state`() {
        val queued = browseOwned(book(DownloadState.REMOTE), DownloadProgress("u", null, queued = true))
        assertEquals(BrowseOwned(BrowseOwnedState.QUEUED), queued)
        val running = browseOwned(book(DownloadState.REMOTE), DownloadProgress("u", 0.4f))
        assertEquals(BrowseOwned(BrowseOwnedState.DOWNLOADING, 0.4f), running)
    }

    @Test
    fun `stored states map to what the tile shows`() {
        assertEquals(BrowseOwnedState.IN_LIBRARY, browseOwned(book(DownloadState.DOWNLOADED), null).state)
        assertEquals(BrowseOwnedState.IN_LIBRARY, browseOwned(book(DownloadState.FAILED, "file:///x"), null).state)
        assertEquals(BrowseOwnedState.FAILED, browseOwned(book(DownloadState.FAILED), null).state)
        assertEquals(BrowseOwnedState.QUEUED, browseOwned(book(DownloadState.QUEUED), null).state)
        assertEquals(BrowseOwnedState.DOWNLOADING, browseOwned(book(DownloadState.DOWNLOADING), null).state)
        // Added, then its file removed: still a library book, not something to add again.
        assertEquals(BrowseOwnedState.IN_LIBRARY, browseOwned(book(DownloadState.REMOTE), null).state)
    }

    @Test
    fun `only downloadable books not already owned can be added`() {
        val owned = mapOf("b" to BrowseOwned(BrowseOwnedState.IN_LIBRARY))
        assertTrue(canAddBrowseBook(remote("a"), owned, canDownload = true))
        assertFalse(canAddBrowseBook(remote("b"), owned, canDownload = true))
        assertFalse(canAddBrowseBook(remote("c", href = null), owned, canDownload = true))
        assertFalse(canAddBrowseBook(remote("a"), owned, canDownload = false))
    }

    @Test
    fun `addable follows the open server and the owned books`() {
        val state = BrowseLibrariesState(
            saved = listOf(server()),
            serverId = 2L,
            listing = BrowseListing(books = listOf(remote("a"), remote("b"), remote("c", href = null))),
            owned = mapOf("b" to BrowseOwned(BrowseOwnedState.DOWNLOADING)),
        )
        assertEquals(listOf("a"), state.addable.map { it.remoteId })
        assertTrue(state.copy(saved = listOf(server(canDownload = false))).addable.isEmpty())
    }

    @Test
    fun `size is only quoted when every book gives one`() {
        assertEquals(300L, browseKnownSize(listOf(remote("a"), remote("b", size = 200L))))
        assertNull(browseKnownSize(listOf(remote("a"), remote("b", size = null))))
        assertNull(browseKnownSize(emptyList()))
    }

    @Test
    fun `a saved catalog book follows its own catalog's download permission`() {
        val saved = book(DownloadState.REMOTE).copy(browseServerId = 2L)
        val main = book(DownloadState.REMOTE).copy(url = "calibre:a")
        val state = LibraryUiState(canDownload = false, downloadableBrowse = setOf(2L))
        assertTrue(state.canDownload(saved))
        assertFalse(state.canDownload(main))
        val locked = LibraryUiState(canDownload = true, downloadableBrowse = emptySet())
        assertFalse(locked.canDownload(saved))
        assertTrue(locked.canDownload(main))
    }

    @Test
    fun `catalog names drop the scheme and keep what tells two apart`() {
        assertEquals("books.example.com", browseCatalogName("https://books.example.com/"))
        assertEquals("books.example.com/opds", browseCatalogName("https://books.example.com/opds/"))
        assertEquals("gutenberg.org/ebooks.opds", browseCatalogName("https://www.gutenberg.org/ebooks.opds/"))
        assertEquals("nas.local:8083", browseCatalogName("http://nas.local:8083"))
        assertEquals("not a url", browseCatalogName("not a url"))
        assertEquals("nas.local/opds?library=2", browseCatalogName("https://nas.local/opds?library=2"))
        assertEquals(
            "nas.local/opds?library=2&apikey=…",
            browseCatalogName("https://nas.local/opds?library=2&apikey=s3cret"),
        )
        assertEquals("nas.local/opds?api%6bey=…", browseCatalogName("https://nas.local/opds?api%6bey=s3cret"))
    }

    @Test
    fun `a further page adds what is new and carries the next cursor`() {
        val first = BrowseListing(
            categories = listOf(BrowseCategory("c1", "One")),
            books = listOf(remote("a"), remote("b")),
            nextPage = "p2",
        )
        val second = BrowseListing(
            categories = listOf(BrowseCategory("c1", "One"), BrowseCategory("c2", "Two")),
            books = listOf(remote("b"), remote("c")),
            complete = false,
            nextPage = null,
        )
        val merged = first.append(second)
        assertEquals(listOf("c1", "c2"), merged.categories.map { it.id })
        assertEquals(listOf("a", "b", "c"), merged.books.map { it.remoteId })
        assertFalse(merged.complete)
        assertNull(merged.nextPage)
    }

    @Test
    fun `a first page never shows the same entry twice`() {
        val page = BrowseListing(
            categories = listOf(BrowseCategory("c1", "One"), BrowseCategory("c1", "Again")),
            books = listOf(remote("a"), remote("a"), remote("b")),
        ).unique()
        assertEquals(listOf("One"), page.categories.map { it.title })
        assertEquals(listOf("a", "b"), page.books.map { it.remoteId })
    }

    @Test
    fun `a walked catalog is reused until asked fresh or the connection changes`() = runTest {
        val cache = BrowseWalkCache()
        var walks = 0
        val walk: suspend () -> BrowseWalk = { walks++; BrowseWalk(listOf(remote("a")), complete = true) }
        val key = BrowseWalkCache.Key(serverId = 2, addedAt = 1, accountKey = "k")

        cache.get(key, fresh = true, walk)
        cache.get(key, fresh = false, walk)
        assertEquals(1, walks)

        cache.get(key, fresh = true, walk)
        assertEquals(2, walks)

        cache.get(key.copy(addedAt = 2), fresh = false, walk)
        assertEquals(3, walks)

        cache.forget(2)
        cache.get(key.copy(addedAt = 2), fresh = false, walk)
        assertEquals(4, walks)
    }

    @Test
    fun `a slow catalog walk does not block another saved server`() = runTest {
        val cache = BrowseWalkCache()
        val slowKey = BrowseWalkCache.Key(serverId = 2, addedAt = 1, accountKey = "slow")
        val fastKey = BrowseWalkCache.Key(serverId = 3, addedAt = 1, accountKey = "fast")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = async {
            cache.get(slowKey, fresh = true) {
                started.complete(Unit)
                release.await()
                BrowseWalk(listOf(remote("slow")), complete = true)
            }
        }
        started.await()

        val fast = async {
            cache.get(fastKey, fresh = true) { BrowseWalk(listOf(remote("fast")), complete = true) }
        }
        runCurrent()
        assertTrue(fast.isCompleted)
        assertEquals("fast", fast.await().books.single().remoteId)

        release.complete(Unit)
        slow.await()
        var repeated = false
        cache.get(fastKey, fresh = false) {
            repeated = true
            BrowseWalk(emptyList(), complete = true)
        }
        assertFalse(repeated)
    }

    @Test
    fun `a removed catalog cannot restore an old in-flight walk`() = runTest {
        val cache = BrowseWalkCache()
        val key = BrowseWalkCache.Key(serverId = 2, addedAt = 1, accountKey = "old")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = async {
            cache.get(key, fresh = true) {
                started.complete(Unit)
                release.await()
                BrowseWalk(listOf(remote("old")), complete = true)
            }
        }
        started.await()
        cache.forget(2)
        release.complete(Unit)
        slow.await()

        val fresh = cache.get(key, fresh = false) { BrowseWalk(listOf(remote("new")), complete = true) }
        assertEquals("new", fresh.books.single().remoteId)
    }

    @Test
    fun `browse cover cache identity changes with the saved connection`() {
        val first = server().copy(addedAt = 1)
        val sameAddressNewConnection = first.copy(addedAt = 2)
        val otherAccount = first.copy(username = "someone-else")
        val url = "https://books.example.com/cover/1"
        assertFalse(browseCoverUrl(url, first) == browseCoverUrl(url, sameAddressNewConnection))
        assertFalse(browseCoverUrl(url, first) == browseCoverUrl(url, otherAccount))
        assertTrue(browseCoverUrl("$url#liseur-bookorbit=book", first).contains("#liseur-bookorbit=book&liseur-browse="))
        assertTrue(browseCoverRequestHeader(first.id, browseCoverUrl(url, first)).startsWith("2:"))
        assertEquals(
            browseCoverRequestHeader(first.id, browseCoverUrl(url, first)),
            browseCoverRequestHeader(first.id, browseCoverUrl("$url#liseur-browse=${"0".repeat(24)}", first)),
        )
    }

    @Test
    fun `only the Gutenberg browse root hides the shortcut`() {
        val root = server().copy(
            kind = ServerKind.CUSTOM,
            baseUrl = StarterCatalog.BROWSE_URL,
            catalogUrl = StarterCatalog.BROWSE_URL,
        )
        assertTrue(BrowseLibrariesState(saved = listOf(root)).gutenbergSaved)
        assertFalse(BrowseLibrariesState(saved = listOf(root.copy(catalogUrl = StarterCatalog.Category.POPULAR.url))).gutenbergSaved)
        assertFalse(BrowseLibrariesState(saved = listOf(server())).gutenbergSaved)
    }
}
