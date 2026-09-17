package com.chmouel.liseur.data.liseursync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.settings.SettingsSyncRepository
import com.chmouel.liseur.data.settings.SyncableSetting
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

/**
 * Settings sync, which is last-writer-wins over a store that can refuse
 * a write and still answer `200`.
 *
 * That combination is the whole difficulty, and every test here is some
 * shape of it. The server's upsert keeps whichever side is newer, so a
 * push that loses is indistinguishable from one that lands unless the
 * merged body is read; and because the two sides then decide what to do
 * next by comparing against what they think was agreed, a single wrong
 * note about an exchange is not a one-off mistake. It is a key that will
 * never move again, on a device whose reader can see it is wrong and has
 * no way to say so.
 */
class LiseurSyncSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var syncState: SettingsSyncRepository
    private val values = mutableMapOf<String, String>()
    private val refused = mutableSetOf<String>()
    private var clock = NOW
    private val seen = mutableListOf<Pair<String, String?>>()

    @Before
    fun open() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        syncState = SettingsSyncRepository(
            PreferenceDataStoreFactory.create { folder.newFile("settings_sync.preferences_pb") },
        )
        values.clear()
        refused.clear()
        seen.clear()
        clock = NOW
    }

    @After
    fun close() = server.close()

    // -- The refused push -------------------------------------------------

    @Test
    fun `a push the server keeps its own value for is taken, not invented`() = runTest {
        values["reader.font_size"] = "120"
        enqueueGet()
        // The server holds a newer value and says so in the merged body,
        // which is the only place the refusal appears at all.
        enqueuePut("reader.font_size" to Entry("140", LATER))

        val exchanged = sync()

        assertEquals(1, exchanged)
        assertEquals("140", values["reader.font_size"])
        val agreed = syncState.allLastSynced(ACCOUNT)["reader.font_size"]!!
        assertEquals("140", agreed.value)
        assertEquals(LATER, agreed.serverTimestamp)
    }

    @Test
    fun `a refused push settles instead of asking again forever`() = runTest {
        values["reader.font_size"] = "120"
        enqueueGet()
        enqueuePut("reader.font_size" to Entry("140", LATER))
        sync()

        // Second pass: both sides now hold the server's value, so there
        // is nothing to say and nothing to send.
        enqueueGet("reader.font_size" to Entry("140", LATER))
        val exchanged = sync()

        assertEquals(0, exchanged)
        assertEquals(2, gets())
        assertEquals(1, puts())
    }

    @Test
    fun `a key the server says nothing about is offered again`() = runTest {
        values["reader.font_size"] = "120"
        enqueueGet()
        // A merged body that simply omits the key: nothing was agreed,
        // so nothing may be written down as agreed.
        enqueuePut()
        sync()

        assertNull(syncState.allLastSynced(ACCOUNT)["reader.font_size"])

        enqueueGet()
        enqueuePut("reader.font_size" to Entry("120", NOW))
        sync()
        assertEquals(2, puts())
    }

    // -- Conflicts --------------------------------------------------------

    @Test
    fun `an edit made after the server's wins`() = runTest {
        agree("reader.font_size", "120", NOW)
        values["reader.font_size"] = "150"
        // The reader changed it here at LATER + 1; the server's copy is
        // from LATER. Arrival order would hand this to the server.
        changedAt("reader.font_size", LATER + 1)
        enqueueGet("reader.font_size" to Entry("140", LATER))
        enqueuePut("reader.font_size" to Entry("150", LATER + 1))

        sync()

        assertEquals("150", values["reader.font_size"])
        assertEquals("150", pushed()["reader.font_size"])
    }

    @Test
    fun `an edit made before the server's loses`() = runTest {
        agree("reader.font_size", "120", NOW)
        values["reader.font_size"] = "150"
        changedAt("reader.font_size", NOW + 1)
        enqueueGet("reader.font_size" to Entry("140", LATER))

        sync()

        assertEquals("140", values["reader.font_size"])
        // Nothing to push: the local edit lost, so no PUT was made.
        assertEquals(0, puts())
    }

    @Test
    fun `a local edit is dated when it was made, not when it is sent`() = runTest {
        agree("reader.font_size", "120", NOW)
        values["reader.font_size"] = "150"
        changedAt("reader.font_size", NOW + 5)
        clock = LATER + 9_000
        enqueueGet()
        enqueuePut("reader.font_size" to Entry("150", NOW + 5))

        sync()

        val sent = JSONObject(requests().last { it.first == "PUT" }.second!!)
            .getJSONObject("settings")
            .getJSONObject("reader.font_size")
        assertEquals("1970-01-01T00:01:40.005Z", sent.getString("updated_at"))
    }

    // -- Values this build cannot use -------------------------------------

    @Test
    fun `a value this build does not understand is left alone`() = runTest {
        values["reader.font"] = "literata"
        refused += "reader.font"
        enqueueGet("reader.font" to Entry("some-font-from-a-newer-build", LATER))

        val exchanged = sync()

        assertEquals(0, exchanged)
        // Crucially, the local default was not pushed over the choice.
        assertEquals(0, puts())
        assertEquals("literata", values["reader.font"])
        assertNull(syncState.allLastSynced(ACCOUNT)["reader.font"])
    }

    // -- The account the baseline belongs to ------------------------------

    @Test
    fun `settings arrive from a second account with older timestamps`() = runTest {
        agree("reader.font_size", "120", LATER, account = ACCOUNT)
        values["reader.font_size"] = "120"
        // A different server, whose clock and history are its own. Its
        // timestamp is older than the first account's, which is exactly
        // the case that stalls if the two baselines are confused.
        enqueueGet("reader.font_size" to Entry("140", NOW))
        enqueuePut("reader.font_size" to Entry("140", NOW))

        sync(account = OTHER)

        assertEquals("140", values["reader.font_size"])
    }

    // -- A book on screen -------------------------------------------------

    @Test
    fun `a reader setting is not applied under an open book`() = runTest {
        values["reader.font_size"] = "120"
        values["app.theme_mode"] = "light"
        enqueueGet(
            "reader.font_size" to Entry("140", LATER),
            "app.theme_mode" to Entry("dark", LATER),
        )

        sync(canApplyReaderSettings = false)

        assertEquals("120", values["reader.font_size"])
        assertEquals("dark", values["app.theme_mode"])
        // Held back, not pushed back: the open book must not be reflowed,
        // and this device has nothing of its own to say about the key.
        assertEquals(0, puts())
        // Nothing recorded for the held-back key, so the next pass, with
        // the book closed, still knows it is owed.
        assertNull(syncState.allLastSynced(ACCOUNT)["reader.font_size"])

        enqueueGet(
            "reader.font_size" to Entry("140", LATER),
            "app.theme_mode" to Entry("dark", LATER),
        )
        sync()
        assertEquals("140", values["reader.font_size"])
    }

    // -- A server that does not have the route ----------------------------

    @Test
    fun `a plain 404 means settings are not served and is asked once`() = runTest {
        server.enqueue(MockResponse(code = 404, body = "404 page not found"))

        assertEquals(-1, sync())
        assertEquals(-1, sync())
        assertEquals(1, gets())
    }

    @Test
    fun `a 404 the route itself answered is a failure, not a missing feature`() = runTest {
        server.enqueue(
            MockResponse(
                code = 404,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = """{"error":"no_such_account"}""",
            ),
        )

        var threw = false
        try {
            sync()
        } catch (_: LiseurSyncRejection) {
            threw = true
        }
        assertTrue(threw)
    }

    // -- First connect ----------------------------------------------------

    @Test
    fun `a configured phone offers its settings to an empty server`() = runTest {
        values["reader.font_size"] = "150"
        values["app.theme_mode"] = "dark"
        enqueueGet()
        enqueuePut(
            "reader.font_size" to Entry("150", NOW),
            "app.theme_mode" to Entry("dark", NOW),
        )

        assertEquals(2, sync())
        assertEquals(setOf("reader.font_size", "app.theme_mode"), pushed().keys)
    }

    @Test
    fun `a fresh phone takes the account's settings`() = runTest {
        values["reader.font_size"] = "100"
        values["app.theme_mode"] = "light"
        enqueueGet(
            "reader.font_size" to Entry("140", LATER),
            "app.theme_mode" to Entry("dark", LATER),
        )

        assertEquals(2, sync())
        assertEquals("140", values["reader.font_size"])
        assertEquals("dark", values["app.theme_mode"])
        assertEquals(0, puts())
    }

    @Test
    fun `a setting the server lost is offered again`() = runTest {
        agree("reader.font_size", "120", NOW)
        values["reader.font_size"] = "120"
        enqueueGet()
        enqueuePut("reader.font_size" to Entry("120", LATER))

        sync()

        assertEquals("120", pushed()["reader.font_size"])
    }

    // -- Harness ----------------------------------------------------------

    private class Entry(val value: String, val updatedAt: Long)

    /**
     * The settings this fake device has, which is exactly the keys the
     * test declared in [values].
     *
     * Derived rather than fixed, because a key the server does not have
     * is always offered to it — that is the point of the re-seed — so a
     * registry with a spare key in it makes every "nothing was pushed"
     * assertion in this file test the spare key instead of the subject.
     */
    private fun settings(): List<SyncableSetting> =
        values.keys.toList().map { key ->
            SyncableSetting(
                key = key,
                read = { values[key] ?: "" },
                write = { v ->
                    if (key in refused) {
                        false
                    } else {
                        values[key] = v
                        true
                    }
                },
            )
        }

    /**
     * One instance across a test, as `AppContainer` holds one across a
     * run. A fresh object per call would quietly lose everything the
     * last pass learned — which is how a server found not to serve
     * settings gets asked again anyway.
     */
    private val sync by lazy {
        LiseurSyncSettings(
            syncState = syncState,
            settings = settings(),
            now = { clock },
        )
    }

    private suspend fun sync(
        account: String = ACCOUNT,
        canApplyReaderSettings: Boolean = true,
    ): Int = sync.sync(
        accountKey = account,
        baseUrl = server.url("/").toString().removeSuffix("/"),
        credentials = RemoteCredentials.Bearer("token"),
        canApplyReaderSettings = canApplyReaderSettings,
    )

    private suspend fun agree(
        key: String,
        value: String,
        at: Long,
        account: String = ACCOUNT,
    ) = syncState.recordSynced(
        account,
        mapOf(key to SettingsSyncRepository.SyncedEntry(value, at)),
    )

    private suspend fun changedAt(key: String, at: Long) {
        // Two looks: the first seeds the baseline, the second is the
        // change, which is how the tracker stamps one.
        syncState.observeLocal(mapOf(key to "seed"), at)
        syncState.observeLocal(mapOf(key to values.getValue(key)), at)
    }

    private fun body(vararg entries: Pair<String, Entry>): String {
        val settings = JSONObject()
        for ((key, entry) in entries) {
            settings.put(
                key,
                JSONObject()
                    .put("value", entry.value)
                    .put("updated_at", iso(entry.updatedAt)),
            )
        }
        return JSONObject().put("settings", settings).toString()
    }

    private fun enqueueGet(vararg entries: Pair<String, Entry>) =
        server.enqueue(MockResponse(code = 200, body = body(*entries)))

    private fun enqueuePut(vararg entries: Pair<String, Entry>) =
        server.enqueue(MockResponse(code = 200, body = body(*entries)))

    /**
     * Every request the server has been sent, drained once and kept.
     *
     * `takeRequest` consumes the queue, so counting GETs and then PUTs
     * off the live queue would make the second count zero and every
     * assertion about it vacuously wrong.
     */
    private fun requests(): List<Pair<String, String?>> {
        while (true) {
            val request = server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: break
            seen += request.method to request.body?.utf8()
        }
        return seen
    }

    /** The settings carried by the last PUT. */
    private fun pushed(): Map<String, String> {
        val last = requests().lastOrNull { it.first == "PUT" }?.second ?: return emptyMap()
        val settings = JSONObject(last).getJSONObject("settings")
        return settings.keys().asSequence()
            .associateWith { settings.getJSONObject(it).getString("value") }
    }

    private fun gets() = requests().count { it.first == "GET" }

    private fun puts() = requests().count { it.first == "PUT" }

    private companion object {
        const val ACCOUNT = "liseursync|https://books.example.com|account-1"
        const val OTHER = "liseursync|https://other.example.com|account-2"
        const val NOW = 100_000L
        const val LATER = 200_000L

        fun iso(millis: Long): String =
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                java.time.Instant.ofEpochMilli(millis),
            )
    }
}
