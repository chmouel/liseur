package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SessionTransmission
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.StatsRange
import java.net.InetAddress
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncWireContractTest {
    // These responses came from liseur-sync's TestInsightsSnapshotWireFixture,
    // not the Android test response builder. Only the request nonce is echoed.
    @Test
    fun `actual Go responses prove measured reading without double counting`() = runBlocking {
        val capabilities = fixture("capabilities")
        val response = fixture("response")
        val candidate = fixture("request").getJSONArray("candidates").getJSONObject(0)
        val today = LocalDate.parse(response.getString("today"))
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).build()
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target.endsWith("/capabilities")) {
                            return MockResponse(code = 200, body = capabilities.toString())
                        }
                        val body = JSONObject(requireNotNull(request.body).utf8())
                        val sent = body.getJSONArray("candidates").getJSONObject(0)
                        val keys = candidate.keys().asSequence().toSet()
                        assertEquals(keys, sent.keys().asSequence().toSet())
                        keys.forEach { key -> assertEquals(key, candidate.get(key), sent.get(key)) }
                        assertEquals(response.getString("calendar_from"), body.getString("calendar_from"))
                        assertEquals(response.getString("calendar_to"), body.getString("calendar_to"))
                        return MockResponse(
                            code = 200,
                            body = JSONObject(response.toString())
                                .put("snapshot_id", body.getString("snapshot_id")).toString(),
                        )
                    }
                }
                val account = RemoteServer(
                    kind = ServerKind.LISEUR_SYNC, baseUrl = "http://127.0.0.1:${server.port}",
                    username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "current-device",
                    userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
                    catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
                    liseurTokenCipher = CredentialCipher.encrypt("fixture-token"),
                    liseurAccountId = capabilities.getString("account_id"),
                )
                db.remoteServerDao().upsert(account)
                db.workIdentityDao().upsert(
                    WorkAlias("book", account.accountKey, candidate.getString("work_id"), confidence = "high", resolvedAt = 1),
                )
                val end = Instant.parse(candidate.getString("ended_at")).toEpochMilli()
                val session = ReadingSession(
                    bookUrl = "book", startedAt = Instant.parse(candidate.getString("started_at")).toEpochMilli(),
                    endedAt = end, lastCheckpointAt = end, durationMs = candidate.getLong("active_ms"),
                    startProgression = candidate.getDouble("start_progression"),
                    endProgression = candidate.getDouble("end_progression"), idleMs = candidate.getLong("idle_ms"),
                )
                val sessionId = db.readingSessionDao().insert(session)
                val payload = JSONObject(candidate.toString()).apply { remove("device_id") }
                db.sessionTransmissionDao().insert(
                    SessionTransmission(account.accountKey, sessionId, candidate.getString("device_id"), payload.toString()),
                )
                val client = LiseurSyncSnapshots(
                    db.remoteServerDao(), db.readingSessionDao(), db.sessionTransmissionDao(), db.workIdentityDao(),
                )
                val context = requireNotNull(client.discover()) { "Actual server capabilities were rejected" }
                val result = requireNotNull(client.read(
                    context, db.readingSessionDao().allOnce(), StatsRange.ALL_TIME, today, DayOfWeek.MONDAY,
                )) { "Actual server snapshot was rejected" }
                assertTrue(context.capabilities.activeMs)
                assertEquals(30.0, result.totals.summary.activeMinutes, 0.0)
                assertEquals(30.0, result.totals.overlapMinutes, 0.0)
                assertEquals(1, result.totals.summary.sessions)
                assertEquals(1, result.totals.overlapSessions)
                assertEquals(1, result.totals.combinedStreak)
                assertEquals(listOf(today), result.totals.days.map { it.date })
                val combinedMinutes = result.totals.summary.activeMinutes +
                    session.durationMs / 60_000.0 - result.totals.overlapMinutes
                assertEquals(30.0, combinedMinutes, 0.0)
            }
        } finally {
            db.close()
            CredentialCipher.keyForTesting = null
        }
    }

    private fun fixture(name: String): JSONObject =
        requireNotNull(javaClass.getResourceAsStream("/liseur-sync-insights/$name.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
}
