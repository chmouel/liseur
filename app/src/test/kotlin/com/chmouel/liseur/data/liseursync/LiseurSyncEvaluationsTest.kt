package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.evaluationExport
import com.chmouel.liseur.data.remote.ServerKind
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncEvaluationsTest {
    private lateinit var db: LiseurDatabase
    private lateinit var http: MockWebServer
    private lateinit var account: RemoteServer
    private lateinit var sync: LiseurSyncEvaluations
    private val heads = mutableMapOf<String, JSONObject>()
    private val workHeads = mutableMapOf("work" to heads)
    private val receipts = mutableMapOf<String, Pair<String, String>>()
    private val missingWorks = mutableSetOf<String>()
    private val resolutions = mutableListOf<JSONObject>()
    private var resolvedWork = "resolved"
    private var evaluationWriter = "original-installation"
    private var resolutionConfidence = "high"
    private val posts = mutableListOf<String>()
    private var failNextWrite = false
    private var rejectBeforeAcceptance = false
    private var onRead: (() -> Unit)? = null
    private var onWrite: (() -> Unit)? = null

    @Before
    fun open() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        http = MockWebServer()
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.substringBefore('?')
                if (path == "/v1/works/resolve") {
                    resolutions += JSONObject(request.body!!.utf8())
                    return MockResponse(body = JSONObject().put("work_id", resolvedWork).put("confidence", resolutionConfidence).toString())
                }
                val work = path.split('/')[3]
                if (work in missingWorks) return MockResponse(code = 404, body = "{\"error\":\"work not found\"}")
                val current = workHeads.getOrPut(work) { mutableMapOf() }
                if (request.method == "POST") {
                    val body = request.body!!.utf8()
                    posts += body
                    val json = JSONObject(body)
                    val id = json.getString("write_id")
                    val prior = receipts[id]
                    if (prior != null && prior != (work to body)) return MockResponse(code = 409, body = "{}")
                    if (rejectBeforeAcceptance) {
                        rejectBeforeAcceptance = false
                        return MockResponse(code = 503, body = "{}")
                    }
                    if (prior == null) {
                        val superseded = receipts.values.any { (acceptedWork, acceptedBody) ->
                            val accepted = JSONObject(acceptedBody)
                            acceptedWork == work && accepted.getString("writer_id") == json.getString("writer_id") &&
                                accepted.getString("field") == json.getString("field") && accepted.getLong("revision") > json.getLong("revision")
                        }
                        receipts[id] = work to body
                        if (!superseded) current[json.getString("field")] = json.put("updated_at", "2026-11-16T10:00:00Z")
                    }
                    onWrite?.invoke()
                    if (failNextWrite) {
                        failNextWrite = false
                        return MockResponse(code = 503, body = "{}")
                    }
                    return MockResponse(body = "{\"status\":\"applied\"}")
                }
                onRead?.invoke()
                return MockResponse(body = JSONObject().put("fields", JSONArray(current.values.toList())).toString())
            }
        }
        http.start(InetAddress.getByName("127.0.0.1"), 0)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java).build()
        account = RemoteServer(
            kind = ServerKind.LISEUR_SYNC,
            baseUrl = "http://127.0.0.1:${http.port}", username = "reader",
            passwordCipher = null, apiKeyCipher = null, accountId = null,
            userId = null, koboTokenCipher = null, canDownload = true,
            addedAt = 1, catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            liseurTokenCipher = CredentialCipher.encrypt("secret"),
        )
        db.remoteServerDao().upsert(account)
        putBook()
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "work", "high", resolvedAt = 1))
        sync = LiseurSyncEvaluations(
            db.remoteServerDao(), db.workIdentityDao(), db.bookDao(), db.bookEvaluationDao(),
            { block -> db.withTransaction { block() } },
            WorkResolver(db.workIdentityDao(), BookFingerprintStore(ApplicationProvider.getApplicationContext(), db.workIdentityDao())),
            writerId = { evaluationWriter },
        )
    }

    @After
    fun close() {
        http.close()
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `lost response replays exact bytes and preserves an edit made during the retry`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "First", "Title", "Author", 1)
        failNextWrite = true
        try { sync.sync(account, null); fail("Expected the interrupted write") } catch (_: java.io.IOException) { }
        dao.edit("book", "review", "Second", "Title", "Author", 2)
        sync.sync(account, null)
        assertEquals(3, posts.size)
        assertEquals(posts[0], posts[1])
        assertNotEquals(posts[1], posts[2])
        assertEquals("Second", dao.get("book", "review")!!.value)
        assertFalse(dao.get("book", "review")!!.owed(dao.state(account.accountKey, "book", "review")))
    }

    @Test
    fun `rating and review sync independently and removed fields remain deleted`() = runTest {
        val dao = db.bookEvaluationDao()
        heads["review"] = JSONObject().put("field", "review").put("value", "From tablet").put("write_id", "tablet").put("updated_at", "2026-11-16T10:00:00Z")
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        sync.sync(account, null)
        assertEquals("9", dao.get("book", "rating")!!.value)
        assertEquals("From tablet", dao.get("book", "review")!!.value)
        assertEquals(1, posts.size)
        dao.edit("book", "review", null, "Title", "Author", 2)
        sync.sync(account, null)
        assertNull(dao.get("book", "review")!!.value)
        assertTrue(heads.getValue("review").isNull("value"))
    }

    @Test
    fun `pull never overwrites a local edit made while awaiting a response`() = runTest {
        val dao = db.bookEvaluationDao()
        heads["review"] = JSONObject().put("field", "review").put("value", "Tablet").put("write_id", "tablet").put("updated_at", "2026-11-16T10:00:00Z")
        onRead = { runBlocking { dao.edit("book", "review", "Local", "Title", "Author", 1) } }
        sync.sync(account, null)
        assertEquals("Local", dao.get("book", "review")!!.value)
    }

    @Test
    fun `disconnect during a response cannot restore account state or data`() = runTest {
        val dao = db.bookEvaluationDao()
        heads["review"] = JSONObject().put("field", "review").put("value", "Tablet").put("write_id", "tablet").put("updated_at", "2026-11-16T10:00:00Z")
        onRead = { runBlocking {
            db.remoteServerDao().upsert(account.copy(username = "someone-else"))
            dao.forgetPeer(account.accountKey)
        } }
        sync.sync(account, null)
        assertNull(dao.get("book", "review"))
        assertTrue(dao.states(account.accountKey).isEmpty())
    }

    @Test
    fun `an offline edit keeps its resolved identity when removed before its first sync`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        removal().deleteByUrls(listOf("book"))
        assertNull(db.bookDao().getByUrl("book"))
        sync.sync(account, null)
        assertEquals(9, heads.getValue("rating").getInt("value"))
        assertNull(dao.get("book", "rating"))
        assertEquals("9", dao.all().single().value)
    }

    @Test
    fun `replacing a file retains its evaluation without attaching it to the new book`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Original book", "Title", "Author", 1)
        sync.sync(account, null)
        removal().contentReplaced("book")
        db.workIdentityDao().deleteAliasIfStale("book", account.accountKey, "work")
        assertNull(dao.get("book", "review"))
        val retained = dao.all().single()
        assertEquals("Original book", retained.value)
        assertEquals(retained.bookUrl, dao.states(account.accountKey).first().bookUrl)
        sync.sync(account, null)
        assertEquals(1, posts.size)
    }

    @Test
    fun `edits survive removal and peer rekeying but are not offered to another account`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        sync.sync(account, null)
        dao.edit("book", "rating", null, "Title", "Author", 2)
        removal().deleteByUrls(listOf("book"))
        sync.sync(account, null)
        assertTrue(heads.getValue("rating").isNull("value"))
        dao.rekeyPeer(account.accountKey, "renamed")
        assertEquals(2, dao.states("renamed").size)
        dao.forgetPeer("renamed")
        assertFalse(dao.all().single().owed(null))
        val exported = evaluationExport(dao.all()).getJSONArray("books").getJSONObject(0)
        assertEquals("Title", exported.getString("title"))
        assertTrue(exported.getJSONObject("rating").isNull("value"))
    }

    private suspend fun putBook(title: String = "Title") {
        db.bookDao().upsert(Book(
            url = "book", title = title, author = "Author", coverPath = null,
            source = null, addedAt = 1, lastOpenedAt = null, workId = "urn:original",
        ))
    }

    private fun removal() = BookRemoval(
        db.bookDao(), db.readingSessionDao(), db.syncPeerStateDao(),
        db.workIdentityDao(), db.readingProgressDao(), db.annotationDao(), db.annotationSyncDao(),
        inTransaction = { block -> db.withTransaction { block() } }, evaluationDao = db.bookEvaluationDao(),
    )

    private fun mergeWork() {
        missingWorks += "work"
        val survivor = workHeads.getOrPut("survivor") { mutableMapOf() }
        heads.forEach { (field, value) -> survivor.putIfAbsent(field, value) }
        receipts.replaceAll { _, receipt -> if (receipt.first == "work") "survivor" to receipt.second else receipt }
        resolvedWork = "survivor"
    }

    @Test
    fun `replacement during pull cannot restore the old review on the new book`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Original book", "Title", "Author", 1)
        sync.sync(account, null)
        onRead = { runBlocking { removal().contentReplaced("book") } }
        sync.sync(account, null)
        assertNull(dao.get("book", "review"))
        assertEquals("Original book", dao.all().single().value)
    }

    @Test
    fun `an acknowledged value cannot overwrite a newer choice after a work merge`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Older choice", "Title", "Author", 1)
        sync.sync(account, null)
        val newer = JSONObject().put("field", "review").put("value", "Newer choice")
            .put("write_id", "tablet").put("updated_at", "2026-11-16T11:00:00Z")
        workHeads["survivor"] = mutableMapOf("review" to newer)
        mergeWork()
        sync.sync(account, null)
        assertNull(db.workIdentityDao().alias("book", account.accountKey))
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "survivor", "high", resolvedAt = 2))
        sync.sync(account, null)
        assertEquals(1, posts.size)
        assertEquals("Newer choice", dao.get("book", "review")!!.value)
    }

    @Test
    fun `a pending request follows a merged work with its exact bytes`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Earlier choice", "Title", "Author", 1)
        failNextWrite = true
        try { sync.sync(account, null); fail("Expected the interrupted write") } catch (_: java.io.IOException) { }
        val newer = JSONObject().put("field", "review").put("value", "Newer choice")
            .put("write_id", "tablet").put("updated_at", "2026-11-16T11:00:00Z")
        workHeads["survivor"] = mutableMapOf("review" to newer)
        mergeWork()
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "survivor", "high", resolvedAt = 2))
        sync.sync(account, null)
        assertEquals(posts.first(), posts.last())
        assertEquals("Newer choice", dao.get("book", "review")!!.value)
    }

    @Test
    fun `a different book imported at a removed path cannot inherit its review`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Original book", "Title", "Author", 1)
        sync.sync(account, null)
        removal().deleteByUrls(listOf("book"))
        putBook("Different book")
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "different-work", "high", resolvedAt = 2))
        assertNull(dao.get("book", "review"))
        sync.sync(account, null)
        assertNull(dao.get("book", "review"))
        assertTrue(workHeads.getValue("different-work").isEmpty())
        assertEquals("Original book", dao.all().single().value)
    }

    @Test
    fun `removal before resolution preserves identifiers and keeps an offline edit syncable`() = runTest {
        db.workIdentityDao().deleteAliasIfStale("book", account.accountKey, "work")
        db.workIdentityDao().upsert(BookFingerprintRow("book", "abc123", "md5", 100, null, 1))
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        removal().deleteByUrls(listOf("book"))
        sync.sync(account, null)
        val sent = resolutions.single().getJSONArray("identifiers")
        assertTrue((0 until sent.length()).any { sent.getJSONObject(it).getString("value") == "abc123" })
        assertTrue((0 until sent.length()).any { sent.getJSONObject(it).getString("value") == "urn:original" })
        assertEquals(9, workHeads.getValue("resolved").getValue("rating").getInt("value"))
    }

    @Test
    fun `an uncertain retained identity never sends private fields`() = runTest {
        db.workIdentityDao().deleteAliasIfStale("book", account.accountKey, "work")
        db.bookEvaluationDao().edit("book", "review", "Private", "Title", "Author", 1)
        removal().deleteByUrls(listOf("book"))
        resolutionConfidence = "low"
        sync.sync(account, null)
        assertTrue(posts.isEmpty())
        assertEquals("Private", db.bookEvaluationDao().all().single().value)
    }

    @Test
    fun `leaving an account prevents retained identifiers and fields being sent to the next account`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Private", "Title", "Author", 1)
        removal().deleteByUrls(listOf("book"))
        dao.forgetPeer(account.accountKey)
        val next = account.copy(username = "another-reader", addedAt = 2)
        db.remoteServerDao().upsert(next)
        sync.sync(next, null)
        assertTrue(resolutions.isEmpty())
        assertTrue(posts.isEmpty())
        assertEquals("Private", dao.all().single().value)
    }

    @Test
    fun `returning an original work after replacement cannot reuse a write id`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        sync.sync(account, null)
        val originalId = JSONObject(posts.first()).getString("write_id")
        removal().contentReplaced("book")
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "work", "high", resolvedAt = 3))
        sync.sync(account, null)
        dao.edit("book", "rating", "6", "Title", "Author", 4)
        sync.sync(account, null)
        assertNotEquals(originalId, JSONObject(posts.last()).getString("write_id"))
        assertEquals(6, heads.getValue("rating").getInt("value"))
    }

    @Test
    fun `a newer edit after reimport wins over a retained unsent edit`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        removal().deleteByUrls(listOf("book"))
        putBook()
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "work", "high", resolvedAt = 2))
        dao.edit("book", "rating", "6", "Title", "Author", 3)
        sync.sync(account, null)
        assertEquals(6, heads.getValue("rating").getInt("value"))
        assertEquals("6", dao.get("book", "rating")!!.value)
    }

    @Test
    fun `a remotely adopted review can be removed and reimported repeatedly`() = runTest {
        heads["review"] = JSONObject().put("field", "review").put("value", "From tablet")
            .put("write_id", "tablet").put("updated_at", "2026-11-16T10:00:00Z")
        repeat(3) { index ->
            putBook()
            db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "work", "high", resolvedAt = index.toLong()))
            sync.sync(account, null)
            removal().deleteByUrls(listOf("book"))
        }
        assertNull(db.bookDao().getByUrl("book"))
        assertEquals(3, db.bookEvaluationDao().retained().size)
        assertEquals(3, db.bookEvaluationDao().all().size)
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `a retained edit can resolve again after its work is merged`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "9", "Title", "Author", 1)
        sync.sync(account, null)
        dao.edit("book", "review", "Offline review", "Title", "Author", 2)
        removal().deleteByUrls(listOf("book"))
        mergeWork()
        sync.sync(account, null)
        sync.sync(account, null)
        assertEquals("Offline review", workHeads.getValue("survivor").getValue("review").getString("value"))
        assertEquals(2, posts.size)
    }

    private suspend fun putCopy() {
        db.bookDao().upsert(db.bookDao().getByUrl("book")!!.copy(url = "copy"))
        db.workIdentityDao().upsert(WorkAlias("copy", account.accountKey, "work", "high", resolvedAt = 1))
    }

    @Test
    fun `newest offline edit wins across copies in one full sync`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        val aliases = db.workIdentityDao().aliasesFor(account.accountKey)
        dao.edit(aliases.last().bookUrl, "review", "Older", "Title", "Author", 1)
        dao.edit(aliases.first().bookUrl, "review", "Latest", "Title", "Author", 2)
        sync.sync(account, null)
        assertEquals("Latest", heads.getValue("review").getString("value"))
        aliases.forEach { assertEquals("Latest", dao.get(it.bookUrl, "review")!!.value) }
    }

    @Test
    fun `a book sync delivers other copies before the newest removal`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        dao.edit("copy", "review", "Older", "Title", "Author", 1)
        dao.edit("book", "review", null, "Title", "Author", 2)
        sync.sync(account, "book")
        assertTrue(heads.getValue("review").isNull("value"))
        assertNull(dao.get("copy", "review")!!.value)
        val count = posts.size
        sync.sync(account, "copy")
        assertEquals(count, posts.size)
        assertTrue(heads.getValue("review").isNull("value"))
    }

    @Test
    fun `each field keeps its edit order across active and retained copies`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        dao.edit("book", "rating", "4", "Title", "Author", 1)
        dao.edit("copy", "rating", "9", "Title", "Author", 2)
        dao.edit("copy", "review", "Older", "Title", "Author", 3)
        dao.edit("book", "review", "Latest", "Title", "Author", 4)
        removal().deleteByUrls(listOf("copy"))
        sync.sync(account, "book")
        assertEquals(9, heads.getValue("rating").getInt("value"))
        assertEquals("Latest", heads.getValue("review").getString("value"))
        for (url in listOf("book", dao.retained().single().bookUrl)) {
            assertEquals("9", dao.get(url, "rating")!!.value)
            assertEquals("Latest", dao.get(url, "review")!!.value)
        }
    }

    @Test
    fun `an edit made during another copy's upload stays owed until the next run`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "First", "Title", "Author", 1)
        dao.edit("copy", "review", "Second", "Title", "Author", 2)
        onWrite = {
            onWrite = null
            runBlocking { dao.edit("copy", "review", "Latest", "Title", "Author", 3) }
        }
        sync.sync(account, null)
        assertEquals("Latest", dao.get("copy", "review")!!.value)
        assertTrue(dao.get("copy", "review")!!.owed(dao.state(account.accountKey, "copy", "review")))
        sync.sync(account, "book")
        assertEquals("Latest", heads.getValue("review").getString("value"))
        assertEquals("Latest", dao.get("book", "review")!!.value)
    }

    @Test
    fun `a pending request settles before a newer edit on another copy`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Older", "Title", "Author", 1)
        failNextWrite = true
        try { sync.sync(account, null); fail("Expected the interrupted write") } catch (_: java.io.IOException) { }
        dao.edit("copy", "review", null, "Title", "Author", 2)
        sync.sync(account, "copy")
        assertEquals(posts[0], posts[1])
        assertTrue(heads.getValue("review").isNull("value"))
        assertNull(dao.get("book", "review")!!.value)
        assertNull(dao.get("copy", "review")!!.value)
    }

    @Test
    fun `a lost response replays unchanged after reconnecting with a new token`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "First", "Title", "Author", 1)
        failNextWrite = true
        try { sync.sync(account, null); fail("Expected the interrupted write") } catch (_: java.io.IOException) { }
        account = account.copy(liseurTokenCipher = CredentialCipher.encrypt("replacement-secret"))
        db.remoteServerDao().upsert(account)
        dao.edit("book", "review", "Latest", "Title", "Author", 2)
        sync.sync(account, null)
        assertEquals(posts[0], posts[1])
        assertEquals("Latest", heads.getValue("review").getString("value"))
        assertNull(dao.state(account.accountKey, "book", "review")!!.sentJson)
    }

    @Test
    fun `confirming an older copy cannot overwrite an acknowledged newer edit`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        db.workIdentityDao().upsert(WorkAlias("copy", account.accountKey, "work", "low", resolvedAt = 1))
        dao.edit("copy", "review", "Older", "Title", "Author", 1)
        dao.edit("book", "review", null, "Title", "Author", 2)
        sync.sync(account, null)
        assertTrue(heads.getValue("review").isNull("value"))
        db.workIdentityDao().confirm("copy", account.accountKey)
        sync.sync(account, "copy")
        assertTrue(heads.getValue("review").isNull("value"))
        assertNull(dao.get("copy", "review")!!.value)
        assertNull(dao.get("book", "review")!!.value)
    }

    @Test
    fun `an older retained edit resolving later cannot restore a removed review`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        db.workIdentityDao().deleteAliasIfStale("copy", account.accountKey, "work")
        dao.edit("copy", "review", "Older", "Title", "Author", 1)
        removal().deleteByUrls(listOf("copy"))
        dao.edit("book", "review", null, "Title", "Author", 2)
        resolutionConfidence = "low"
        resolvedWork = "work"
        sync.sync(account, null)
        assertTrue(heads.getValue("review").isNull("value"))
        resolutionConfidence = "high"
        sync.sync(account, null)
        assertTrue(heads.getValue("review").isNull("value"))
        assertNull(dao.get(dao.retained().single().bookUrl, "review")!!.value)
    }

    @Test
    fun `a superseded edit adopts the server head without reauthoring a remote review`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        db.workIdentityDao().upsert(WorkAlias("copy", account.accountKey, "work", "low", resolvedAt = 1))
        dao.edit("copy", "review", "Older", "Title", "Author", 1)
        dao.edit("book", "review", "Newer local", "Title", "Author", 2)
        sync.sync(account, null)
        heads["review"] = JSONObject().put("field", "review").put("value", "Newest remote")
            .put("write_id", "tablet").put("updated_at", "2026-11-16T11:00:00Z")
        sync.sync(account, null)
        db.workIdentityDao().confirm("copy", account.accountKey)
        val count = posts.size
        sync.sync(account, "copy")
        assertEquals(count, posts.size)
        assertEquals("Newest remote", dao.get("copy", "review")!!.value)
        assertEquals("Newest remote", dao.get("book", "review")!!.value)
        dao.edit("copy", "review", "Fresh edit", "Title", "Author", 3)
        sync.sync(account, "copy")
        assertEquals("Fresh edit", heads.getValue("review").getString("value"))
    }

    @Test
    fun `an unaccepted retry cannot undo a newer deletion after a work merge`() = runTest {
        putCopy()
        val dao = db.bookEvaluationDao()
        db.workIdentityDao().upsert(WorkAlias("copy", account.accountKey, "survivor", "high", resolvedAt = 1))
        dao.edit("book", "review", "Old draft", "Title", "Author", 1)
        rejectBeforeAcceptance = true
        try { sync.sync(account, "book"); fail("Expected failure") } catch (_: java.io.IOException) { }
        dao.edit("copy", "review", null, "Title", "Author", 2)
        sync.sync(account, "copy")
        assertTrue(workHeads.getValue("survivor").getValue("review").isNull("value"))
        mergeWork()
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "survivor", "high", resolvedAt = 2))
        sync.sync(account, null)
        assertTrue(workHeads.getValue("survivor").getValue("review").isNull("value"))
        assertEquals(posts.first(), posts.last())
        assertNull(dao.get("book", "review")!!.value)
        assertNull(dao.get("copy", "review")!!.value)
        assertNull(dao.state(account.accountKey, "book", "review")!!.sentJson)
        val count = posts.size
        sync.sync(account, null)
        assertEquals(count, posts.size)
    }

    @Test
    fun `a restored library can author the same revision without reusing the original phones write id`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Before backup", "Title", "Author", 1)
        sync.sync(account, null)
        val backedUpValue = dao.get("book", "review")!!
        val backedUpState = dao.state(account.accountKey, "book", "review")!!
        dao.edit("book", "review", "Original phone", "Title", "Author", 2)
        sync.sync(account, null)
        val original = JSONObject(posts.last())

        dao.upsert(backedUpValue)
        dao.upsertState(backedUpState)
        evaluationWriter = "restored-installation"
        dao.edit("book", "review", "Restored phone", "Title", "Author", 2)
        sync.sync(account, null)
        val restored = JSONObject(posts.last())
        assertEquals(original.getLong("revision"), restored.getLong("revision"))
        assertNotEquals(original.getString("write_id"), restored.getString("write_id"))
        assertNotEquals(original.getString("writer_id"), restored.getString("writer_id"))
        assertEquals("Restored phone", heads.getValue("review").getString("value"))
        assertNull(dao.state(account.accountKey, "book", "review")!!.sentJson)
    }

    @Test
    fun `a restored pending request keeps its original bytes before a fresh writer sends newer edits`() = runTest {
        val dao = db.bookEvaluationDao()
        dao.edit("book", "review", "Pending at backup", "Title", "Author", 1)
        failNextWrite = true
        try { sync.sync(account, null); fail("Expected failure") } catch (_: java.io.IOException) { }
        evaluationWriter = "restored-installation"
        dao.edit("book", "review", "Restored edit", "Title", "Author", 2)
        sync.sync(account, null)
        assertEquals(posts[0], posts[1])
        assertEquals("original-installation", JSONObject(posts[1]).getString("writer_id"))
        assertEquals("restored-installation", JSONObject(posts[2]).getString("writer_id"))
        assertEquals("Restored edit", heads.getValue("review").getString("value"))
        assertNull(dao.state(account.accountKey, "book", "review")!!.sentJson)
    }
}
