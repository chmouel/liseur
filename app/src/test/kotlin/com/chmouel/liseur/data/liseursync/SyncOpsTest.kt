package com.chmouel.liseur.data.liseursync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wire format, and the promise that makes retries free.
 *
 * The server recognises a repeated op by its id and refuses an id that
 * comes back carrying something else. So everything here is really one
 * property: the same reading position must always describe itself the
 * same way, however many times it is sent.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SyncOpsTest {

    @Test
    fun `the same position always has the same name`() {
        assertEquals(
            SyncOps.opIdFor("device-a", "w-1", 7),
            SyncOps.opIdFor("device-a", "w-1", 7),
        )
    }

    @Test
    fun `two devices at the same revision do not silence each other`() {
        // Both phones read to the same place and each calls it revision
        // seven. Sharing an op id would mean the second one is refused
        // as a contradiction of the first.
        assertNotEquals(
            SyncOps.opIdFor("device-a", "w-1", 7),
            SyncOps.opIdFor("device-b", "w-1", 7),
        )
    }

    @Test
    fun `moving on is a different op`() {
        assertNotEquals(
            SyncOps.opIdFor("device-a", "w-1", 7),
            SyncOps.opIdFor("device-a", "w-1", 8),
        )
    }

    @Test
    fun `a retry sends byte for byte what the first attempt sent`() {
        val op = op()

        assertEquals(SyncOps.toJson(op).toString(), SyncOps.toJson(op).toString())
    }

    @Test
    fun `a locator too large to send is dropped, not fatal`() {
        val huge = JSONObject().put("h", "x".repeat(SyncOps.MAX_LOCATOR_BYTES)).toString()

        val json = SyncOps.toJson(op().copy(locatorJson = huge))

        // The percentage still travels: the other device reopening at
        // roughly the right page beats it not knowing at all.
        assertTrue(json.has("progression"))
        assertNull(json.optJSONObject("locator"))
    }

    @Test
    fun `an empty locator is not sent as an empty object`() {
        assertNull(SyncOps.locatorFor("{}"))
        assertNull(SyncOps.locatorFor(""))
        assertNull(SyncOps.locatorFor(null))
    }

    @Test
    fun `an op survives the round trip`() {
        val json = SyncOps.toJson(op()).put("device_id", "d-1").put("seq", 42L)

        val back = SyncOps.fromJson(json)!!

        assertEquals("op-1", back.opId)
        assertEquals("w-1", back.workId)
        assertEquals(0.5, back.progression, 0.0)
        assertEquals("d-1", back.deviceId)
        assertEquals(42L, back.seq)
        assertEquals(CLIENT_TS, back.clientTs)
    }

    @Test
    fun `an op with no work id is not an op`() {
        assertNull(SyncOps.fromJson(JSONObject().put("op_id", "o").put("progression", 0.1)))
        assertNull(SyncOps.fromJson(JSONObject().put("work_id", "w").put("op_id", "o")))
    }

    @Test
    fun `timestamps are read whether or not the server kept the fraction`() {
        assertEquals(
            SyncOps.parseTime("2024-01-01T00:00:00Z"),
            SyncOps.parseTime("2024-01-01T00:00:00.000Z"),
        )
    }

    @Test
    fun `timestamps are written in UTC`() {
        assertTrue(SyncOps.formatTime(CLIENT_TS).endsWith("Z"))
        assertEquals(CLIENT_TS, SyncOps.parseTime(SyncOps.formatTime(CLIENT_TS)))
    }

    private fun op() = SyncOp(
        opId = "op-1",
        workId = "w-1",
        editionSha = "sha-1",
        clientTs = CLIENT_TS,
        progression = 0.5,
        locatorJson = """{"href":"/c1.xhtml"}""",
    )

    private companion object {
        const val CLIENT_TS = 1_700_000_000_000L
    }
}
