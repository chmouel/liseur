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
 * The settings backup, which keeps one copy per device on the server.
 *
 * This device is the only writer of its copy, so there is no conflict to
 * settle: a value already on the server for a key this device never
 * stored there is its own earlier copy and is restored; after that,
 * whatever this device holds is what the server should hold.
 */
class LiseurSyncSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var syncState: SettingsSyncRepository
    private val values = mutableMapOf<String, String>()
    private val refused = mutableSetOf<String>()
    private val affectsPage = mutableSetOf<String>()
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
        affectsPage.clear()
        seen.clear()
        clock = NOW
    }

    @After
    fun close() = server.close()

    // -- First connect ----------------------------------------------------

    @Test
    fun `a configured phone uploads its settings to an empty server`() = runTest {
        values["reader.font_size"] = "150"
        values["app.theme_mode"] = "dark"
        enqueueGet()
        enqueuePut(
            "reader.font_size" to Entry("150", NOW),
            "app.theme_mode" to Entry("dark", NOW),
        )

        assertEquals(2, sync())
        assertEquals(setOf("reader.font_size", "app.theme_mode"), pushed().keys)
        assertEquals(mapOf("reader.font_size" to "150", "app.theme_mode" to "dark"), stored())
    }

    @Test
    fun `a reinstalled phone gets its own settings back`() = runTest {
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
        assertEquals(mapOf("reader.font_size" to "140", "app.theme_mode" to "dark"), stored())
    }

    @Test
    fun `a value the server already holds is recorded without being sent`() = runTest {
        values["reader.font"] = "bitter"
        enqueueGet("reader.font" to Entry("bitter", NOW))

        assertEquals(0, sync())
        assertEquals(0, puts())
        assertEquals("bitter", stored()["reader.font"])
    }

    // -- After that, this device decides -----------------------------------

    @Test
    fun `a change made here is uploaded`() = runTest {
        record("reader.font", "literata")
        values["reader.font"] = "bitter"
        enqueueGet("reader.font" to Entry("literata", NOW))
        enqueuePut("reader.font" to Entry("bitter", LATER))
        clock = LATER

        assertEquals(1, sync())
        assertEquals("bitter", pushed()["reader.font"])
        assertEquals("bitter", values["reader.font"])
        assertEquals("bitter", stored()["reader.font"])
    }

    @Test
    fun `the server's copy never overrides a setting this device has stored`() = runTest {
        // Whatever the server holds, once this device has stored the key
        // its own value is the one that counts.
        record("reader.font", "bitter")
        values["reader.font"] = "bitter"
        enqueueGet("reader.font" to Entry("vollkorn", LATER))
        enqueuePut("reader.font" to Entry("bitter", LATER + 1))
        clock = LATER + 1

        sync()

        assertEquals("bitter", values["reader.font"])
        assertEquals("bitter", pushed()["reader.font"])
    }

    @Test
    fun `an upload is dated after the copy it replaces even on a slow clock`() = runTest {
        record("reader.font", "literata")
        values["reader.font"] = "bitter"
        // The clock was set back since the last upload.
        enqueueGet("reader.font" to Entry("literata", LATER))
        enqueuePut("reader.font" to Entry("bitter", LATER + 1))
        clock = NOW

        sync()

        val sent = JSONObject(requests().last { it.first == "PUT" }.second!!)
            .getJSONObject("settings").getJSONObject("reader.font")
        assertEquals(iso(LATER + 1), sent.getString("updated_at"))
    }

    @Test
    fun `a setting the server lost is uploaded again`() = runTest {
        record("reader.font_size", "120")
        values["reader.font_size"] = "120"
        enqueueGet()
        enqueuePut("reader.font_size" to Entry("120", LATER))

        sync()

        assertEquals("120", pushed()["reader.font_size"])
    }

    @Test
    fun `a key the server did not keep as sent is offered again`() = runTest {
        values["reader.font_size"] = "120"
        enqueueGet()
        enqueuePut()
        sync()

        assertNull(stored()["reader.font_size"])

        enqueueGet()
        enqueuePut("reader.font_size" to Entry("120", NOW))
        sync()
        assertEquals(2, puts())
        assertEquals("120", stored()["reader.font_size"])
    }

    @Test
    fun `nothing changed means nothing is sent`() = runTest {
        record("reader.font", "bitter")
        values["reader.font"] = "bitter"
        enqueueGet("reader.font" to Entry("bitter", NOW))

        assertEquals(0, sync())
        assertEquals(0, puts())
    }

    // -- Values this build cannot use -------------------------------------

    @Test
    fun `a stored value this build does not understand is replaced by this device's`() = runTest {
        values["reader.font"] = "literata"
        refused += "reader.font"
        enqueueGet("reader.font" to Entry("some-font-from-a-newer-build", LATER))
        enqueuePut("reader.font" to Entry("literata", LATER + 1))

        sync()

        assertEquals("literata", values["reader.font"])
        assertEquals("literata", pushed()["reader.font"])
        assertEquals("literata", stored()["reader.font"])
    }

    @Test
    fun `a value the server could not store is dropped without taking the batch with it`() =
        runTest {
            values["app.dictionary_base_url"] = "https://example.com/" + "x".repeat(5000)
            values["reader.font"] = "bitter"
            enqueueGet()
            enqueuePut("reader.font" to Entry("bitter", NOW))

            sync()

            val sent = pushed()
            assertTrue("reader.font" in sent)
            assertTrue("the oversized value was sent", "app.dictionary_base_url" !in sent)
        }

    // -- A book on screen -------------------------------------------------

    @Test
    fun `a restored reader setting is not applied under an open book`() = runTest {
        values["reader.font_size"] = "120"
        values["app.theme_mode"] = "light"
        enqueueGet(
            "reader.font_size" to Entry("140", LATER),
            "app.theme_mode" to Entry("dark", LATER),
        )

        sync(canApplyReaderSettings = false)

        assertEquals("120", values["reader.font_size"])
        assertEquals("dark", values["app.theme_mode"])
        // Held back, not uploaded over the copy being restored.
        assertEquals(0, puts())
        assertNull(stored()["reader.font_size"])

        enqueueGet(
            "reader.font_size" to Entry("140", LATER),
            "app.theme_mode" to Entry("dark", LATER),
        )
        sync()
        assertEquals("140", values["reader.font_size"])
    }

    @Test
    fun `a setting that relays out the page is held back whatever its prefix`() = runTest {
        values["app.scroll_mode"] = "false"
        affectsPage += "app.scroll_mode"
        enqueueGet("app.scroll_mode" to Entry("true", LATER))

        sync(canApplyReaderSettings = false)

        assertEquals("false", values["app.scroll_mode"])
        assertNull(stored()["app.scroll_mode"])

        enqueueGet("app.scroll_mode" to Entry("true", LATER))
        sync()
        assertEquals("true", values["app.scroll_mode"])
    }

    // -- Servers that do not keep settings per device -----------------------

    @Test
    fun `a server that shares settings across devices is left alone`() = runTest {
        values["reader.font"] = "bitter"
        // An older server: account-wide settings, no scope in the answer.
        server.enqueue(
            MockResponse(
                code = 200,
                body = JSONObject().put("settings", settingsJson("reader.font" to Entry("vollkorn", LATER)))
                    .toString(),
            ),
        )

        assertEquals(-1, sync())
        assertEquals(-1, sync())
        assertEquals("bitter", values["reader.font"])
        assertEquals(1, gets())
        assertEquals(0, puts())
        assertEquals(emptyMap<String, String>(), stored())
    }

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

    // -- Things that move while a pass runs --------------------------------

    @Test
    fun `an account switched away from mid-run neither writes here nor is recorded`() = runTest {
        values["reader.font"] = "bitter"
        enqueueGet("reader.font" to Entry("literata", LATER))

        sync(connected = false)

        assertEquals("bitter", values["reader.font"])
        assertEquals(emptyMap<String, String>(), stored())
    }

    @Test
    fun `a setting changed while the pull is in flight is not overwritten`() = runTest {
        enqueueGet("reader.font" to Entry("literata", LATER))
        var reads = 0
        var written: String? = null
        val sync = LiseurSyncSettings(
            syncState = syncState,
            settings = listOf(
                SyncableSetting(
                    key = "reader.font",
                    read = { if (reads++ == 0) "bitter" else "vollkorn" },
                    write = { v -> written = v; true },
                ),
            ),
            now = { clock },
        )

        sync.sync(
            accountKey = ACCOUNT,
            baseUrl = server.url("/").toString().removeSuffix("/"),
            credentials = RemoteCredentials.Bearer("token"),
        )

        assertNull("the newer choice was written over", written)
        assertNull(stored()["reader.font"])
    }

    @Test
    fun `each account has its own record`() = runTest {
        record("reader.font_size", "120", account = ACCOUNT)
        values["reader.font_size"] = "120"
        // This device never stored anything on the other account, so
        // what that server holds for it is restored there.
        enqueueGet("reader.font_size" to Entry("140", NOW))

        sync(account = OTHER)

        assertEquals("140", values["reader.font_size"])
        assertEquals("120", stored(ACCOUNT)["reader.font_size"])
        assertEquals("140", stored(OTHER)["reader.font_size"])
    }

    // -- Harness ----------------------------------------------------------

    private class Entry(val value: String, val updatedAt: Long)

    /**
     * The settings this fake device has, which is exactly the keys the
     * test declared in [values], so a "nothing was sent" assertion is
     * about the subject and not about a spare key.
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
                affectsOpenBook = key.startsWith("reader.") || key in affectsPage,
            )
        }

    /** One instance across a test, as `AppContainer` holds one across a run. */
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
        connected: Boolean = true,
    ): Int = sync.sync(
        accountKey = account,
        baseUrl = server.url("/").toString().removeSuffix("/"),
        credentials = RemoteCredentials.Bearer("token"),
        canApplyReaderSettings = { canApplyReaderSettings },
        stillConnected = { connected },
    )

    private suspend fun record(key: String, value: String, account: String = ACCOUNT) =
        syncState.recordStored(account, mapOf(key to value))

    private suspend fun stored(account: String = ACCOUNT) = syncState.allStored(account)

    private fun settingsJson(vararg entries: Pair<String, Entry>): JSONObject {
        val settings = JSONObject()
        for ((key, entry) in entries) {
            settings.put(
                key,
                JSONObject()
                    .put("value", entry.value)
                    .put("updated_at", iso(entry.updatedAt)),
            )
        }
        return settings
    }

    private fun body(vararg entries: Pair<String, Entry>): String =
        JSONObject()
            .put("settings", settingsJson(*entries))
            .put("scope", "device")
            .toString()

    private fun enqueueGet(vararg entries: Pair<String, Entry>) =
        server.enqueue(MockResponse(code = 200, body = body(*entries)))

    private fun enqueuePut(vararg entries: Pair<String, Entry>) =
        server.enqueue(MockResponse(code = 200, body = body(*entries)))

    /**
     * Every request the server has been sent, drained once and kept, so
     * counting GETs and then PUTs does not empty the queue under the
     * second count.
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
