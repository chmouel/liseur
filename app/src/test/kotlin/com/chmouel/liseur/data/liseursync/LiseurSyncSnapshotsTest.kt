package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SessionTransmission
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.ComparisonDirection
import com.chmouel.liseur.ui.stats.ReadingStatsUiState
import com.chmouel.liseur.ui.stats.ReadingStatsViewModel
import com.chmouel.liseur.ui.stats.StatsProvenance
import java.net.InetAddress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
class LiseurSyncSnapshotsTest {
    private lateinit var db: LiseurDatabase
    private lateinit var server: MockWebServer
    private lateinit var account: RemoteServer
    private val today = LocalDate.of(2026, 9, 5)
    private val zone = ZoneId.of("Europe/Paris")
    private var earliest = today
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private var changeResponse: (JSONObject) -> Unit = {}
    private var changeCapabilities: (JSONObject) -> Unit = {}
    private var changeToken: (JSONObject) -> Unit = {}
    private var capabilitiesCode = 200
    private var duringRequest: suspend (JSONObject) -> Unit = {}

    @Before
    fun open() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java).build()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        account = RemoteServer(
            kind = ServerKind.LISEUR_SYNC, baseUrl = "http://127.0.0.1:${server.port}",
            username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "device",
            userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            liseurTokenCipher = CredentialCipher.encrypt("secret"), liseurAccountId = "account",
        )
        db.remoteServerDao().upsert(account)
        db.workIdentityDao().upsert(
            WorkAlias("book", account.accountKey, "work", confidence = "high", resolvedAt = 1),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target == "/v1/token") {
                    return ok(JSONObject(
                        """{"account_id":"account","device_id":"device","session_active_ms":true,"scopes":["sync"]}""",
                    ).also(changeToken))
                }
                if (request.target.endsWith("/capabilities")) {
                    if (capabilitiesCode != 200) return MockResponse(code = capabilitiesCode, body = """{"error":"forbidden"}""")
                    return ok(JSONObject(CAPABILITIES).also(changeCapabilities))
                }
                val body = JSONObject(request.body!!.utf8())
                requests += body
                runBlocking { duringRequest(body) }
                return ok(answer(body).also(changeResponse))
            }
        }
    }

    @After
    fun close() {
        server.close()
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `snapshot carries original payload and actual device identity`() = runTest {
        val id = sitting()
        val evidence = transmit(id)
        val result = read()!!
        val candidate = requests.single().getJSONArray("candidates").getJSONObject(0)
        assertEquals("device-original", candidate.getString("device_id"))
        assertEquals(JSONObject(evidence.payload).getString("session_id"), candidate.getString("session_id"))
        assertFalse(candidate.has("active_ms"))
        assertEquals(zone, result.zone)
        assertEquals(10.0, result.totals.overlapMinutes, 0.0)
        assertEquals(90.0, result.totals.summary.activeMinutes, 0.0)
        assertEquals(today, result.totals.days.last().date)
        assertEquals(11, result.totals.combinedStreak)
    }

    @Test
    fun `lost acknowledgement and acknowledgement-only updates retain proof`() = runTest {
        val id = sitting()
        transmit(id)
        duringRequest = { db.readingSessionDao().markUploaded(listOf(id), 55) }
        assertNotNull(read())
    }

    @Test
    fun `alias bookkeeping changes do not invalidate unchanged statistics`() = runTest {
        transmit(sitting())
        duringRequest = {
            val alias = db.workIdentityDao().alias("book", account.accountKey)!!
            db.workIdentityDao().upsert(alias.copy(annotationsReconciledAt = 55, resolvedAt = 99, seeded = true))
        }
        assertNotNull(read())
    }

    @Test
    fun `unattempted local reading is not claimed as a server candidate`() = runTest {
        sitting()
        assertNotNull(read())
        assertEquals(0, requests.single().getJSONArray("candidates").length())
        assertEquals(listOf(today.toString()), requests.single().getJSONArray("local_active_days").let {
            (0 until it.length()).map(it::getString)
        })
    }

    @Test
    fun `pre-upgrade attempted identity is never reconstructed as proof`() = runTest {
        sitting(unknown = true)
        assertNull(read())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `out-of-window legacy sessions contribute active days but need no overlap proof`() = runTest {
        val id = sitting(unknown = true)
        val old = db.readingSessionDao().get(id)!!
        db.readingSessionDao().deleteForBook("book")
        val earlier = today.minusMonths(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.readingSessionDao().insert(old.copy(
            id = 0, startedAt = earlier - 600_000, endedAt = earlier, lastCheckpointAt = earlier,
        ))
        transmit(sitting())
        assertNotNull(read())
        assertEquals(1, requests.single().getJSONArray("candidates").length())
        assertEquals(2, requests.single().getJSONArray("local_active_days").length())
        assertNull(read(StatsRange.ALL_TIME))
    }

    @Test
    fun `uploading an out-of-window sitting during the request cannot affect its totals`() = runTest {
        val earlier = today.minusMonths(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val oldId = db.readingSessionDao().insert(ReadingSession(
            bookUrl = "book", startedAt = earlier - 600_000, endedAt = earlier, lastCheckpointAt = earlier,
            durationMs = 600_000, startProgression = 0.1, endProgression = 0.2,
        ))
        sitting()
        duringRequest = { transmit(oldId) }
        assertNotNull(read())
        assertEquals(0, requests.single().getJSONArray("candidates").length())
    }

    @Test
    fun `first transmission during response invalidates the captured input`() = runTest {
        val id = sitting()
        duringRequest = { transmit(id) }
        assertNull(read())
    }

    @Test
    fun `local membership alias and credential changes reject an in-flight answer`() = runTest {
        sitting()
        duringRequest = { sitting() }
        assertNull(read())
        duringRequest = {
            db.workIdentityDao().upsert(
                WorkAlias("book", account.accountKey, "different", confidence = "high", resolvedAt = 2),
            )
        }
        assertNull(read())
        duringRequest = { db.remoteServerDao().upsert(account.copy(liseurTokenCipher = CredentialCipher.encrypt("new"))) }
        assertNull(read())
    }

    @Test
    fun `wrong identity zone date coverage and incomplete proof are all refused`() = runTest {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("account_id", "other") },
            { it.put("timezone", "UTC") },
            { it.put("snapshot_id", "stale") },
            { it.put("today", today.minusDays(1).toString()) },
            { it.put("attribution_version", 1) },
            { it.put("version", 2) },
            { it.put("complete", false) },
            { it.remove("calendar_from") },
            { it.put("calendar_to", today.minusDays(1).toString()) },
            { it.put("to", today.minusDays(1).toString()) },
            { it.put("stats_revision", 7) },
            { it.getJSONObject("summary").put("total_active_minutes", "NaN") },
            { it.getJSONObject("summary").put("sessions", 2_147_483_648L) },
            { it.getJSONObject("overlap").put("total_active_minutes", 100.0) },
        )
        for (mutation in mutations) {
            changeResponse = mutation
            assertNull(read())
        }
    }

    @Test
    fun `total work and day overlap accept only sub-millisecond summation excess`() = runTest {
        transmit(sitting())
        for (field in listOf("total", "work", "day")) {
            for (amount in listOf(0.1 + 0.2, 0.3 + 0.75 / 60_000)) {
                changeResponse = { overlapRounding(it, field, amount) }
                val result = read()
                assertNotNull("$field: $amount", result)
                val totals = result!!.totals
                assertEquals(0.3, totals.overlapMinutes, 0.0)
                assertEquals(0.3, totals.overlapBooks.getValue("work").first, 0.0)
                assertEquals(0.3, totals.overlapDays.getValue(today), 0.0)
            }
        }
    }

    @Test
    fun `meaningful overlap excess is refused rather than clamped`() = runTest {
        transmit(sitting())
        for (field in listOf("total", "work", "day", "all")) {
            changeResponse = { overlapRounding(it, field, 0.3 + 1.25 / 60_000) }
            assertNull(field, read())
        }
    }

    private fun overlapRounding(response: JSONObject, field: String, amount: Double) {
        response.getJSONObject("summary").put("total_active_minutes", 0.3)
        response.getJSONArray("works").getJSONObject(0).put("total_active_minutes", 0.3)
        response.getJSONArray("days").getJSONObject(0).put("minutes", 0.3)
        val overlap = response.getJSONObject("overlap")
        overlap.put("total_active_minutes", if (field == "total" || field == "all") amount else 0.3)
        overlap.getJSONArray("works").getJSONObject(0)
            .put("total_active_minutes", if (field == "work" || field == "all") amount else 0.3)
        overlap.getJSONArray("days").getJSONObject(0)
            .put("minutes", if (field == "day" || field == "all") amount else 0.3)
    }

    @Test
    fun `unsupported all-time capability and oversized candidate sets fall back without truncation`() = runTest {
        changeCapabilities = { it.put("all_time", false) }
        assertNull(read(StatsRange.ALL_TIME))
        assertTrue(requests.isEmpty())
        transmit(sitting())
        transmit(sitting())
        changeCapabilities = { it.put("max_candidates", 1) }
        assertNull(read())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `final version-one capability contract supports all-time without optional refinements`() = runTest {
        changeCapabilities = {
            it.remove("all_time")
            it.remove("account_id")
            it.remove("max_body_bytes")
            it.remove("max_local_active_days")
        }
        val context = client().discover()!!
        assertTrue(context.capabilities.activeMs)
        assertTrue(context.capabilities.allTime)
        assertEquals(1_048_576, context.capabilities.maxBodyBytes)
        assertEquals(25_000, context.capabilities.maxLocalActiveDays)
        assertNotNull(read(StatsRange.ALL_TIME))
        assertEquals(1, requests.size)
        changeResponse = { it.put("account_id", "unrelated") }
        assertNull(read(StatsRange.ALL_TIME))
    }

    @Test
    fun `no authenticated account identity means local fallback rather than accepting any account echo`() = runTest {
        changeCapabilities = { it.remove("account_id") }
        db.remoteServerDao().upsert(account.copy(liseurAccountId = null))
        assertNull(read())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `advertised active-day and full-body limits reject complete proof rather than truncating`() = runTest {
        val id = sitting()
        val original = db.readingSessionDao().get(id)!!
        db.readingSessionDao().insert(original.copy(
            id = 0, startedAt = original.startedAt - 86_400_000,
            endedAt = original.endedAt!! - 86_400_000, lastCheckpointAt = original.lastCheckpointAt - 86_400_000,
        ))
        changeCapabilities = { it.put("max_local_active_days", 1) }
        assertNull(read())
        assertTrue(requests.isEmpty())
        changeCapabilities = { it.put("max_body_bytes", 64) }
        assertNull(read())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `body limit counts actual UTF-8 bytes including original device identity`() = runTest {
        transmit(sitting(), deviceId = "\u00e9".repeat(16))
        assertNotNull(read())
        val body = requests.single().toString()
        val bytes = body.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > body.length)
        changeCapabilities = { it.put("max_body_bytes", (bytes + body.length) / 2) }
        requests.clear()
        assertNull(read())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `sync-only tokens negotiate measured duration without requesting insights`() = runTest {
        capabilitiesCode = 403
        assertTrue(client().supportsMeasuredSessions())
        assertEquals(1, server.requestCount)
        assertEquals("/v1/token", server.takeRequest().target)
        assertFalse(db.remoteServerDao().get()!!.canReadInsights)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `token duration negotiation requires explicit support and the same identity`() = runTest {
        changeToken = { it.remove("session_active_ms") }
        assertFalse(client().supportsMeasuredSessions())
        changeToken = { it.put("account_id", "other") }
        assertFalse(client().supportsMeasuredSessions())
        changeToken = { it.put("device_id", "other-device") }
        assertFalse(client().supportsMeasuredSessions())
    }

    @Test
    fun `forbidden capabilities correct the insights permission without blocking sync-only duration support`() = runTest {
        db.remoteServerDao().upsert(account.copy(canReadInsights = true))
        capabilitiesCode = 403
        assertNull(client().discover())
        assertFalse(db.remoteServerDao().get()!!.canReadInsights)
        assertTrue(client().supportsMeasuredSessions())
        assertFalse(db.remoteServerDao().get()!!.canReadInsights)
    }

    @Test
    fun `discovery account must match stored identity but can identify a legacy account without rekeying`() = runTest {
        changeCapabilities = { it.put("account_id", "someone-else") }
        assertNull(client().discover())
        changeCapabilities = { it.put("account_id", "account") }
        db.remoteServerDao().upsert(account.copy(liseurAccountId = null))
        val oldKey = db.remoteServerDao().get()!!.accountKey
        assertNotNull(read())
        assertEquals(oldKey, db.remoteServerDao().get()!!.accountKey)
        assertNull(db.remoteServerDao().get()!!.liseurAccountId)
    }

    @Test
    fun `all-time history before 2024 is dense and fetched in bounded nonoverlapping chunks`() = runTest {
        earliest = LocalDate.of(1990, 1, 1)
        val result = read(StatsRange.ALL_TIME)!!
        assertEquals(earliest, result.totals.days.first().date)
        assertEquals(today, result.totals.days.last().date)
        assertEquals(today.toEpochDay() - earliest.toEpochDay() + 1, result.totals.days.size.toLong())
        assertEquals(90.0, result.totals.days.sumOf { it.activeMinutes }, 0.0)
        val spans = requests.map {
            LocalDate.parse(it.getString("calendar_from")) to LocalDate.parse(it.getString("calendar_to"))
        }.sortedBy { it.first }
        assertTrue(spans.size > 2)
        spans.forEach { assertTrue(it.second.toEpochDay() - it.first.toEpochDay() < 4_000) }
        spans.zipWithNext().forEach { (a, b) -> assertEquals(a.second.plusDays(1), b.first) }
        assertEquals(1, requests.map { it.getString("snapshot_id") }.distinct().size)
    }

    @Test
    fun `a changed revision or missing chunk discards the whole calendar`() = runTest {
        earliest = LocalDate.of(1990, 1, 1)
        changeResponse = { if (requests.size > 1) it.put("stats_revision", "8") }
        assertNull(read(StatsRange.ALL_TIME))
        requests.clear()
        changeResponse = { if (requests.size > 1) it.remove("calendar_to") }
        assertNull(read(StatsRange.ALL_TIME))
    }

    @Test
    fun `duplicate or out-of-window sparse days are not accepted as complete coverage`() = runTest {
        changeResponse = { it.getJSONArray("days").put(it.getJSONArray("days").getJSONObject(0)) }
        assertNull(read())
        changeResponse = {
            it.getJSONArray("days").getJSONObject(0).put("date", today.plusDays(1).toString())
        }
        assertNull(read())
    }

    @Test
    fun `malformed capabilities cannot enable a snapshot`() = runTest {
        changeCapabilities = { it.put("timezone", "not/a-zone") }
        assertNull(client().discover())
        changeCapabilities = { it.put("version", 0) }
        assertNull(client().discover())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `view model publishes the full account-zone union with phone-zone comparisons`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val id = sitting()
            val previous = today.minusMonths(1).minusDays(1).atTime(12, 0).atZone(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
            db.readingSessionDao().insert(ReadingSession(
                bookUrl = "book", startedAt = previous - 600_000, endedAt = previous,
                lastCheckpointAt = previous, durationMs = 600_000,
            ))
            val instant = today.atTime(0, 30).atZone(zone).toInstant()
            val model = ReadingStatsViewModel(
                db.readingSessionDao(), db.bookDao(), db.readingProgressDao(),
                initialRange = StatsRange.THIS_MONTH,
                zone = { ZoneId.of("UTC") }, now = { instant.atZone(it) },
                snapshotSource = client(),
                aliases = db.workIdentityDao().observeAliases(),
                liveAccounts = db.remoteServerDao().observe().map { it?.let(LiveIdentity::from) },
            )
            models.put("snapshot", model)
            model.refreshServerInsights()
            val ready = model.state.first {
                it is ReadingStatsUiState.Ready && it.provenance == StatsProvenance.ALL_DEVICES
            } as ReadingStatsUiState.Ready
            assertEquals(today, ready.today)
            assertEquals(120 * 60_000L, ready.headline.totalMs)
            assertEquals(120 * 60_000L, ready.stats.books.single().totalMs)
            assertEquals(120 * 60_000L, ready.stats.recent.last().totalMs)
            assertEquals(ComparisonDirection.LESS, ready.headline.comparison!!.direction)
            assertEquals(100, ready.headline.comparison!!.percent)
            transmit(id)
            db.readingSessionDao().markUploaded(listOf(id), 99)
            db.bookDao().upsert(Book(
                url = "book", title = "Retitled", author = null, coverPath = null,
                source = null, addedAt = 1, lastOpenedAt = null,
            ))
            val afterUpload = model.state.first {
                it is ReadingStatsUiState.Ready && it.stats.books.singleOrNull()?.title == "Retitled"
            } as ReadingStatsUiState.Ready
            assertEquals(ready.headline, afterUpload.headline)
            assertEquals(ready.snapshotId, afterUpload.snapshotId)
            assertEquals(StatsProvenance.ALL_DEVICES, afterUpload.provenance)
            sitting()
            val fallback = model.state.first {
                it is ReadingStatsUiState.Ready && it.provenance == StatsProvenance.THIS_DEVICE
            } as ReadingStatsUiState.Ready
            assertEquals(today.minusDays(1), fallback.today)
            assertNull(fallback.snapshotId)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `superseded range generation cannot publish its late snapshot`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            duringRequest = { arrived.complete(Unit); release.await() }
            val model = ReadingStatsViewModel(
                db.readingSessionDao(), db.bookDao(), db.readingProgressDao(),
                initialRange = StatsRange.THIS_MONTH, zone = { zone }, now = { today.atStartOfDay(it) },
                initialWeekStart = DayOfWeek.MONDAY,
                snapshotSource = client(),
                aliases = db.workIdentityDao().observeAliases(),
                liveAccounts = db.remoteServerDao().observe().map { it?.let(LiveIdentity::from) },
            )
            models.put("generation", model)
            model.refreshServerInsights()
            arrived.await()
            model.selectRange(StatsRange.THIS_WEEK)
            release.complete(Unit)
            val ready = model.state.first {
                it is ReadingStatsUiState.Ready && it.provenance == StatsProvenance.ALL_DEVICES
            } as ReadingStatsUiState.Ready
            assertEquals(StatsRange.THIS_WEEK, ready.range)
            assertEquals(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                ready.stats.recent.first().date)
        } finally {
            release.complete(Unit)
            models.clear()
            Dispatchers.resetMain()
        }
    }

    private fun client() = LiseurSyncSnapshots(
        db.remoteServerDao(), db.readingSessionDao(), db.sessionTransmissionDao(), db.workIdentityDao(),
    )

    private suspend fun read(range: StatsRange = StatsRange.THIS_MONTH): CompleteStatsSnapshot? {
        val client = client()
        val context = client.discover() ?: return null
        return client.read(context, db.readingSessionDao().allOnce(), range, today, DayOfWeek.MONDAY)
    }

    private suspend fun sitting(unknown: Boolean = false): Long {
        val end = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "book", startedAt = end - 600_000, endedAt = end,
                lastCheckpointAt = end, durationMs = 1_800_000,
                startProgression = 0.1, endProgression = 0.2, idleMs = 0,
                legacyEvidenceUnknown = unknown,
            ),
        )
    }

    private suspend fun transmit(id: Long, deviceId: String = "device-original"): SessionTransmission {
        val payload = SessionUploads.toJson(db.readingSessionDao().get(id)!!, "original-key", "work", null)!!
        return SessionTransmission(account.accountKey, id, deviceId, payload.toString()).also {
            db.sessionTransmissionDao().insert(it)
        }
    }

    private fun answer(request: JSONObject): JSONObject {
        val start = LocalDate.parse(request.getString("calendar_from"))
        val end = LocalDate.parse(request.getString("calendar_to"))
        val hasOverlap = request.getJSONArray("candidates").length() > 0
        val response = JSONObject().apply {
            put("version", 1)
            put("attribution_version", 2)
            put("account_id", "account")
            put("timezone", zone.id)
            put("snapshot_id", request.getString("snapshot_id"))
            put("stats_revision", "7")
            put("complete", true)
            put("first_activity_day", earliest.toString())
            put("today", today.toString())
            put("range_days", if (request.optString("range") == "all") 0 else 5)
            if (request.has("from")) put("from", request.getString("from")).put("to", request.getString("to"))
            put("calendar_from", start.toString())
            put("calendar_to", end.toString())
            put("summary", JSONObject("""{"total_active_minutes":90,"sessions":4,"streak_days":10}"""))
            put("works", JSONArray().put(JSONObject("""{"work_id":"work","title":"Book","sessions":4,"total_active_minutes":90}""")
                .put("last_read_at", today.atStartOfDay(zone).toInstant().toString())))
            put("days", JSONArray().apply { if (earliest in start..end) put(day(earliest, 90, 4)) })
            put("overlap", JSONObject().apply {
                put("total_active_minutes", if (hasOverlap) 10 else 0)
                put("sessions", if (hasOverlap) 1 else 0)
                put("works", JSONArray().apply {
                    if (hasOverlap) put(JSONObject("""{"work_id":"work","total_active_minutes":10,"sessions":1}"""))
                })
                put("days", JSONArray().apply { if (hasOverlap && today in start..end) put(day(today, 10, 1)) })
            })
            put("combined_streak_days", 11)
        }
        return response
    }

    private fun day(date: LocalDate, minutes: Int, sessions: Int) =
        JSONObject().put("date", date.toString()).put("minutes", minutes).put("sessions", sessions)

    private fun ok(body: JSONObject) = MockResponse(code = 200, body = body.toString())

    private companion object {
        const val CAPABILITIES = """{"version":1,"active_ms":true,"attribution_version":2,"timezone":"Europe/Paris","max_candidates":10000,"max_calendar_days":4000}"""
    }
}
