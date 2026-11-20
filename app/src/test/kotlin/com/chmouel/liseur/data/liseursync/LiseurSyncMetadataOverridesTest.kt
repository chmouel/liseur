package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import com.chmouel.liseur.data.db.MetadataOverrideSync
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import com.chmouel.liseur.data.library.FieldEdit
import com.chmouel.liseur.data.remote.ServerKind
import java.io.File
import java.net.InetAddress
import java.time.Instant
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncMetadataOverridesTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var db: LiseurDatabase
    private lateinit var http: MockWebServer
    private lateinit var account: RemoteServer
    private lateinit var repository: BookMetadataOverrideRepository
    private lateinit var sync: LiseurSyncMetadataOverrides
    private lateinit var remote: FakeRemote

    @Before
    fun open() = runTest {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        remote = FakeRemote()
        http = MockWebServer()
        http.dispatcher = remote.dispatcher
        http.start(InetAddress.getByName("127.0.0.1"), 0)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        repository = BookMetadataOverrideRepository(
            bookDao = db.bookDao(),
            overrideDao = db.bookMetadataOverrideDao(),
            coversDir = folder.newFolder("covers"),
            inTransaction = { block -> db.withTransaction { block() } },
            encodeCoverJpeg = { it },
        )

        account = RemoteServer(
            kind = ServerKind.LISEUR_SYNC,
            baseUrl = "http://127.0.0.1:${http.port}",
            username = "reader",
            passwordCipher = null,
            apiKeyCipher = null,
            accountId = null,
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = 1,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
            liseurTokenCipher = CredentialCipher.encrypt("secret"),
        )
        db.remoteServerDao().upsert(account)
        db.bookDao().upsert(
            Book(
                url = "book",
                title = "File title",
                author = "File author",
                coverPath = "file-cover.jpg",
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                fileTitle = "File title",
                fileAuthor = "File author",
            ),
        )
        db.workIdentityDao().upsert(WorkAlias("book", account.accountKey, "work", "high", resolvedAt = 1))
        sync = LiseurSyncMetadataOverrides(
            servers = db.remoteServerDao(),
            identities = db.workIdentityDao(),
            books = db.bookDao(),
            overrides = db.bookMetadataOverrideDao(),
            repository = repository,
            inTransaction = { block -> db.withTransaction { block() } },
            writerId = { "test-device" },
        )
    }

    @After
    fun close() {
        http.close()
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `push sends owed text overrides with empty vs null and cover put then delete`() = runTest {
        repository.applyTextEdits(
            bookUrl = "book",
            title = FieldEdit.Set(""),
            author = FieldEdit.Set("temp"),
            description = FieldEdit.Set(""),
        )
        repository.applyTextEdits(bookUrl = "book", author = FieldEdit.Reset)
        val coverBytes = "cover-push".toByteArray()
        assertTrue(repository.setCoverFromBytes("book", coverBytes))

        sync.sync(account, null)

        val titlePost = remote.metadataPosts.single { it.getString("field") == MetadataOverrideFields.TITLE }
        assertEquals("", titlePost.getString("value"))
        val authorPost = remote.metadataPosts.single { it.getString("field") == MetadataOverrideFields.AUTHOR }
        assertTrue(authorPost.isNull("value"))
        val descriptionPost = remote.metadataPosts.single { it.getString("field") == MetadataOverrideFields.DESCRIPTION }
        assertEquals("", descriptionPost.getString("value"))
        assertEquals(listOf("PUT"), remote.coverMethods)
        assertEquals(coverBytes.toList(), remote.coverPutBodies.single().toList())

        repository.clearCoverToEmpty("book")
        sync.sync(account, null)
        assertEquals(listOf("PUT"), remote.coverMethods)
        assertTrue(db.bookDao().getByUrl("book")!!.coverOverridden)

        repository.resetCover("book")
        sync.sync(account, null)
        assertEquals(listOf("PUT", "DELETE"), remote.coverMethods)
    }

    @Test
    fun `pull applies remote text and cover then clears them from tombstones`() = runTest {
        remote.fields[MetadataOverrideFields.TITLE] = "Remote title"
        remote.fields[MetadataOverrideFields.AUTHOR] = "Remote author"
        remote.fields[MetadataOverrideFields.DESCRIPTION] = "Remote description"
        remote.coverBytes = "cover-pull".toByteArray()
        remote.coverSha = BookMetadataOverrideRepository.sha256Hex(remote.coverBytes!!)
        remote.coverUpdatedAt = 1_000

        sync.sync(account, null)

        val pulled = db.bookDao().getByUrl("book")!!
        assertEquals("Remote title", pulled.userTitle)
        assertEquals("Remote author", pulled.userAuthor)
        assertEquals("Remote description", pulled.userDescription)
        assertTrue(pulled.titleOverridden)
        assertTrue(pulled.authorOverridden)
        assertTrue(pulled.descriptionOverridden)
        assertTrue(pulled.coverOverridden)
        assertEquals(remote.coverSha, pulled.userCoverSha256)
        val firstCoverPath = pulled.userCoverPath
        assertNotNull(firstCoverPath)
        assertTrue(File(firstCoverPath!!).isFile)
        assertEquals(remote.coverBytes!!.toList(), File(firstCoverPath).readBytes().toList())

        remote.fields[MetadataOverrideFields.TITLE] = null
        remote.fields[MetadataOverrideFields.AUTHOR] = null
        remote.fields[MetadataOverrideFields.DESCRIPTION] = null
        remote.coverSha = null
        remote.coverBytes = null
        remote.coverUpdatedAt = 2_000

        sync.sync(account, null)

        val cleared = db.bookDao().getByUrl("book")!!
        assertFalse(cleared.titleOverridden)
        assertFalse(cleared.authorOverridden)
        assertFalse(cleared.descriptionOverridden)
        assertFalse(cleared.coverOverridden)
        assertEquals("File title", cleared.title)
        assertEquals("File author", cleared.author)
        assertNull(cleared.userCoverPath)
        assertFalse(File(firstCoverPath).exists())
    }

    @Test
    fun `a cover removed here is not sent and a pull does not put it back`() = runTest {
        remote.coverBytes = "remote-cover".toByteArray()
        remote.coverSha = BookMetadataOverrideRepository.sha256Hex(remote.coverBytes!!)
        remote.coverUpdatedAt = 1_000
        sync.sync(account, null)
        assertTrue(db.bookDao().getByUrl("book")!!.coverOverridden)

        repository.clearCoverToEmpty("book")
        sync.sync(account, null)

        assertEquals(emptyList<String>(), remote.coverMethods)
        val emptied = db.bookDao().getByUrl("book")!!
        assertTrue(emptied.coverOverridden)
        assertNull(emptied.userCoverPath)
        assertNull(emptied.effectiveCover)
    }

    @Test
    fun `a cover the server does not have leaves the work aliases alone`() = runTest {
        remote.coverBytes = "vanished-cover".toByteArray()
        remote.coverSha = BookMetadataOverrideRepository.sha256Hex(remote.coverBytes!!)
        remote.coverUpdatedAt = 1_000
        remote.coverGetCode = 404

        sync.sync(account, null)

        assertNotNull(db.workIdentityDao().alias("book", account.accountKey))
        val current = db.bookDao().getByUrl("book")!!
        assertFalse(current.coverOverridden)
        assertEquals("File title", current.title)
    }

    @Test
    fun `a value the server refuses is dropped so the corrected one can go`() = runTest {
        repository.applyTextEdits(bookUrl = "book", title = FieldEdit.Set("First try"))
        remote.metadataPostCode = 400

        sync.sync(account, null)

        assertNull(db.bookMetadataOverrideDao().state(account.accountKey, "book", MetadataOverrideFields.TITLE)?.sentJson)
        assertNull(remote.fields[MetadataOverrideFields.TITLE])

        remote.metadataPostCode = null
        repository.applyTextEdits(bookUrl = "book", title = FieldEdit.Set("Corrected"))
        sync.sync(account, null)

        assertEquals("Corrected", remote.fields[MetadataOverrideFields.TITLE])
        assertEquals("Corrected", db.bookDao().getByUrl("book")!!.userTitle)
    }

    @Test
    fun `a response is not applied once the book at the url is another generation`() = runTest {
        remote.fields[MetadataOverrideFields.TITLE] = "Remote title"
        remote.beforeMetadataGet = {
            db.bookDao().refreshIndexedFile(
                url = "book",
                title = "Replaced title",
                author = "Replaced author",
                coverPath = null,
                fileModifiedAt = 99,
                workId = "work",
                seriesName = null,
                seriesIndex = null,
            )
        }

        sync.sync(account, null)

        val current = db.bookDao().getByUrl("book")!!
        assertFalse(current.titleOverridden)
        assertEquals("Replaced title", current.title)
    }

    @Test
    fun `override dao rekey and forget move states then retire local revisions`() = runTest {
        val dao = db.bookMetadataOverrideDao()
        dao.upsertRevision(
            MetadataOverrideRevision(
                bookUrl = "book",
                field = MetadataOverrideFields.TITLE,
                revision = 3,
                retiredRevision = 0,
                updatedAt = 1,
            ),
        )
        dao.upsertRevision(
            MetadataOverrideRevision(
                bookUrl = "book",
                field = MetadataOverrideFields.COVER,
                revision = 2,
                retiredRevision = 0,
                updatedAt = 1,
            ),
        )
        dao.upsertState(
            MetadataOverrideSync(
                peerId = "old-peer",
                bookUrl = "book",
                field = MetadataOverrideFields.TITLE,
                workId = "work",
                ackedRevision = 1,
            ),
        )
        dao.upsertPoll(com.chmouel.liseur.data.db.MetadataOverridePoll("old-peer", 99))

        assertEquals(2, dao.countForPeer("old-peer"))
        dao.rekeyPeer("old-peer", "new-peer")
        assertEquals(0, dao.countForPeer("old-peer"))
        assertEquals(2, dao.countForPeer("new-peer"))

        dao.forgetPeer("new-peer")
        assertEquals(0, dao.countForPeer("new-peer"))
        val revisions = dao.allRevisions().associateBy { it.field }
        assertEquals(3L, revisions.getValue(MetadataOverrideFields.TITLE).retiredRevision)
        assertEquals(2L, revisions.getValue(MetadataOverrideFields.COVER).retiredRevision)
    }

    private class FakeRemote {
        val fields = linkedMapOf<String, String?>()
        val metadataPosts = mutableListOf<JSONObject>()
        val coverMethods = mutableListOf<String>()
        val coverPutBodies = mutableListOf<ByteArray>()
        var coverSha: String? = null
        var coverUpdatedAt: Long = 0
        var coverBytes: ByteArray? = null
        var coverGetCode: Int? = null
        var metadataPostCode: Int? = null
        var beforeMetadataGet: (suspend () -> Unit)? = null

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.substringBefore('?')
                return when {
                    path.endsWith("/metadata") && request.method == "POST" -> {
                        val body = JSONObject(request.body!!.utf8())
                        metadataPosts += body
                        val refusal = metadataPostCode
                        if (refusal != null) {
                            MockResponse(code = refusal, body = "{}")
                        } else {
                            val field = body.getString("field")
                            fields[field] = if (body.isNull("value")) null else body.getString("value")
                            MockResponse(body = "{}")
                        }
                    }
                    path.endsWith("/metadata") && request.method == "GET" -> {
                        beforeMetadataGet?.let { runBlocking { it() } }
                        val remoteFields = JSONArray()
                        for ((field, value) in fields) {
                            remoteFields.put(
                                JSONObject()
                                    .put("field", field)
                                    .put("value", value ?: JSONObject.NULL)
                                    .put("updated_at", Instant.ofEpochMilli(maxOf(coverUpdatedAt, 1)).toString()),
                            )
                        }
                        MockResponse(
                            body = JSONObject()
                                .put("fields", remoteFields)
                                .put(
                                    "cover",
                                    JSONObject()
                                        .put("sha256", coverSha ?: JSONObject.NULL)
                                        .put("updated_at", Instant.ofEpochMilli(coverUpdatedAt).toString()),
                                )
                                .toString(),
                        )
                    }
                    path.endsWith("/cover") && request.method == "PUT" -> {
                        val bytes = request.body!!.utf8().toByteArray()
                        coverMethods += "PUT"
                        coverPutBodies += bytes
                        coverSha = BookMetadataOverrideRepository.sha256Hex(bytes)
                        coverUpdatedAt += 1_000
                        coverBytes = bytes
                        MockResponse(
                            body = JSONObject()
                                .put("sha256", coverSha)
                                .put("updated_at", Instant.ofEpochMilli(coverUpdatedAt).toString())
                                .toString(),
                        )
                    }
                    path.endsWith("/cover") && request.method == "DELETE" -> {
                        coverMethods += "DELETE"
                        coverSha = null
                        coverBytes = null
                        coverUpdatedAt += 1_000
                        MockResponse(
                            body = JSONObject()
                                .put("sha256", JSONObject.NULL)
                                .put("updated_at", Instant.ofEpochMilli(coverUpdatedAt).toString())
                                .toString(),
                        )
                    }
                    path.endsWith("/cover") && request.method == "GET" -> {
                        val bytes = coverBytes
                        val refusal = coverGetCode
                        if (refusal != null) {
                            MockResponse(code = refusal, body = "{}")
                        } else if (bytes == null) {
                            MockResponse(code = 404, body = "{}")
                        } else {
                            MockResponse(body = bytes.toString(Charsets.UTF_8))
                        }
                    }
                    else -> MockResponse(code = 404, body = "{}")
                }
            }
        }
    }
}
