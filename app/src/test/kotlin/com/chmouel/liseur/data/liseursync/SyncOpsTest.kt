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

    // -- What a position may not be ---------------------------------------
    //
    // A server that writes `"progression": null` — which is what
    // JSON.stringify does with a NaN — used to arrive here as a position
    // of exactly zero and send the reader back to page one. None of
    // these may be read as a number.

    @Test
    fun `a null progression is not a position of zero`() {
        assertNull(SyncOps.fromJson(wire().put("progression", JSONObject.NULL)))
    }

    @Test
    fun `an absent progression is not a position of zero`() {
        val json = wire()
        json.remove("progression")

        assertNull(SyncOps.fromJson(json))
    }

    @Test
    fun `a progression that is not a number is not a position of zero`() {
        assertNull(SyncOps.fromJson(wire().put("progression", "abc")))
        assertNull(SyncOps.fromJson(wire().put("progression", JSONObject())))
    }

    @Test
    fun `a non-finite progression is refused`() {
        // org.json refuses to parse a bare NaN or Infinity, so this is
        // the shape one can still arrive in — and the shape the old
        // optDouble() turned back into a real NaN and stored.
        assertNull(SyncOps.fromJson(wire().put("progression", "NaN")))
        assertNull(SyncOps.fromJson(wire().put("progression", "Infinity")))
        assertNull(SyncOps.fromJson(wire().put("progression", "-Infinity")))
    }

    @Test
    fun `a quoted number is still a position`() {
        // Out of contract, but not ambiguous, and checked either way.
        assertEquals(0.25, SyncOps.fromJson(wire().put("progression", "0.25"))!!.progression, 0.0)
    }

    @Test
    fun `a progression outside the book is refused`() {
        assertNull(SyncOps.fromJson(wire().put("progression", -0.1)))
        assertNull(SyncOps.fromJson(wire().put("progression", 1.5)))
    }

    @Test
    fun `a locator whose own progression is unusable is refused`() {
        // Ops 2145 to 2148: the top-level number had already been
        // coerced to a legitimate-looking zero, but the locator still
        // said what really happened.
        val locator = JSONObject()
            .put("href", "/c1.xhtml")
            .put("locations", JSONObject().put("totalProgression", JSONObject.NULL))

        assertNull(SyncOps.fromJson(wire().put("progression", 0.0).put("locator", locator)))
    }

    @Test
    fun `a locator whose own progression is out of range is refused`() {
        val locator = JSONObject()
            .put("locations", JSONObject().put("totalProgression", 4.2))

        assertNull(SyncOps.fromJson(wire().put("locator", locator)))
    }

    // -- What a position still is -----------------------------------------

    @Test
    fun `the start of the book is a real position`() {
        val op = SyncOps.fromJson(wire().put("progression", 0.0))

        assertEquals(0.0, op!!.progression, 0.0)
    }

    @Test
    fun `the end of the book is a real position`() {
        assertEquals(1.0, SyncOps.fromJson(wire().put("progression", 1.0))!!.progression, 0.0)
    }

    @Test
    fun `an op with no locator is a real position`() {
        // A percentage-only partner sends exactly this.
        assertEquals(0.5, SyncOps.fromJson(wire())!!.progression, 0.0)
    }

    @Test
    fun `a locator with no locations is a real position`() {
        val json = wire().put("locator", JSONObject().put("href", "/c1.xhtml"))

        assertEquals(0.5, SyncOps.fromJson(json)!!.progression, 0.0)
    }

    @Test
    fun `a locator with locations but no progression is a real position`() {
        val locator = JSONObject()
            .put("href", "/c1.xhtml")
            .put("locations", JSONObject().put("position", 208))

        assertEquals(0.5, SyncOps.fromJson(json = wire().put("locator", locator))!!.progression, 0.0)
    }

    // -- Sequencing, which survives the position ---------------------------

    @Test
    fun `an unreadable record keeps its sequence number`() {
        val item = SyncOps.feedItemFrom(
            wire().put("progression", JSONObject.NULL).put("seq", 2145L),
        )!!

        assertEquals(2145L, item.seq)
        assertNull(item.op)
    }

    @Test
    fun `a readable record carries both`() {
        val item = SyncOps.feedItemFrom(wire().put("seq", 2144L))!!

        assertEquals(2144L, item.seq)
        assertEquals(0.5, item.op!!.progression, 0.0)
    }

    @Test
    fun `a record with neither a position nor a sequence number is nothing`() {
        assertNull(SyncOps.feedItemFrom(wire().put("progression", JSONObject.NULL)))
        assertNull(SyncOps.feedItemFrom(JSONObject().put("seq", 0L)))
    }

    private fun wire() = JSONObject()
        .put("op_id", "op-1")
        .put("work_id", "w-1")
        .put("client_ts", SyncOps.formatTime(CLIENT_TS))
        .put("progression", 0.5)

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
