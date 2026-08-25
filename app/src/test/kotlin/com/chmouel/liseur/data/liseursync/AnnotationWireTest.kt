package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether two devices agree about a highlight.
 *
 * Almost every assertion here is really the same one: the server
 * recognises a repeated write by comparing whole payloads, so anything
 * that can vary between two builds of the same mark is a retry that
 * silently becomes a conflict.
 */
class AnnotationWireTest {

    // -- Kinds and colours ------------------------------------------------

    @Test
    fun `a note is a highlight carrying the reader's words`() {
        val item = AnnotationWire.item(mark(kind = AnnotationKind.NOTE, note = "worth arguing with"), WORK, 0, null)
        val sent = JSONObject(item!!.json)
        assertEquals("highlight", sent.getString("kind"))
        assertEquals("worth arguing with", sent.getString("body"))
    }

    @Test
    fun `a highlight with a body comes back as a note`() {
        val record = AnnotationWire.record(
            server(kind = "highlight", body = "worth arguing with"),
        )!!
        assertEquals(
            AnnotationKind.NOTE.name,
            AnnotationWire.toAnnotation(record, BOOK, null).kind,
        )
    }

    @Test
    fun `a highlight with nothing written on it stays a highlight`() {
        val record = AnnotationWire.record(server(kind = "highlight"))!!
        assertEquals(
            AnnotationKind.HIGHLIGHT.name,
            AnnotationWire.toAnnotation(record, BOOK, null).kind,
        )
    }

    @Test
    fun `a bookmark carries neither colour nor words`() {
        val item = AnnotationWire.item(
            mark(kind = AnnotationKind.BOOKMARK, note = "ignored", tint = "YELLOW"),
            WORK, 0, null,
        )
        val sent = JSONObject(item!!.json)
        assertEquals("bookmark", sent.getString("kind"))
        assertFalse(sent.has("body"))
        assertFalse(sent.has("color"))
    }

    @Test
    fun `every colour the server names survives the round trip`() {
        for (color in AnnotationWire.COLORS) {
            val item = AnnotationWire.item(mark(tint = color.uppercase()), WORK, 0, null)
            assertEquals(color, JSONObject(item!!.json).getString("color"))

            val record = AnnotationWire.record(server(color = color))!!
            assertEquals(
                color.uppercase(),
                AnnotationWire.toAnnotation(record, BOOK, null).tint,
            )
        }
    }

    @Test
    fun `a colour this device cannot name is not sent as one it can`() {
        // Rewriting it would be worse than dropping it: the other device
        // would see its own choice quietly changed and change it back.
        val item = AnnotationWire.item(mark(tint = "CHARTREUSE"), WORK, 0, null)
        assertFalse(JSONObject(item!!.json).has("color"))
    }

    // -- Fingerprints ------------------------------------------------------

    @Test
    fun `the same mark hashes the same twice`() {
        val mark = mark()
        assertEquals(
            AnnotationWire.fingerprint(mark, WORK),
            AnnotationWire.fingerprint(mark, WORK),
        )
    }

    @Test
    fun `changing anything the server holds changes the hash`() {
        val base = AnnotationWire.fingerprint(mark(), WORK)
        assertNotEquals(base, AnnotationWire.fingerprint(mark(tint = "BLUE"), WORK))
        assertNotEquals(base, AnnotationWire.fingerprint(mark(note = "new"), WORK))
        assertNotEquals(base, AnnotationWire.fingerprint(mark(text = "other"), WORK))
        assertNotEquals(base, AnnotationWire.fingerprint(mark(updatedAt = 9), WORK))
        assertNotEquals(base, AnnotationWire.fingerprint(mark(), "other-work"))
    }

    @Test
    fun `an accepted write does not leave the mark looking changed`() {
        // The rev moves on every accepted write. A hash that noticed
        // would report the mark as edited the instant it was agreed, and
        // push it again for ever.
        val mark = mark()
        val first = AnnotationWire.item(mark, WORK, baseRev = 0, editionSha = null)!!
        val second = AnnotationWire.item(mark, WORK, baseRev = 1, editionSha = null)!!
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `two devices holding different files of one book do not push at each other`() {
        // edition_sha still goes on the wire — it says which file was
        // marked — but if it counted here, each device would overwrite
        // the other's reference on every run, for ever, over something
        // neither reader can see.
        val mark = mark()
        val mine = AnnotationWire.item(mark, WORK, 0, editionSha = "aaa")!!
        val theirs = AnnotationWire.item(mark, WORK, 0, editionSha = "bbb")!!
        assertEquals(mine.fingerprint, theirs.fingerprint)
        assertEquals("aaa", JSONObject(mine.json).getString("edition_sha"))
    }

    // -- Locators ----------------------------------------------------------

    @Test
    fun `a locator is written the same way however it was built`() {
        val one = """{"href":"/c1.xhtml","locations":{"position":12,"progression":0.5},"title":"One"}"""
        val other = """{"title":"One","locations":{"progression":0.5,"position":12},"href":"/c1.xhtml"}"""
        assertEquals(
            AnnotationWire.canonicalLocator(one),
            AnnotationWire.canonicalLocator(other),
        )
    }

    @Test
    fun `a locator that is not JSON anchors nothing`() {
        assertNull(AnnotationWire.canonicalLocator("not a locator"))
        assertNull(AnnotationWire.canonicalLocator(""))
        assertNull(AnnotationWire.item(mark(locator = "nonsense"), WORK, 0, null))
    }

    @Test
    fun `a locator's nested arrays and numbers keep one spelling`() {
        val canonical = AnnotationWire.canonicalLocator(
            """{"a":[1,2.5,true,null],"b":{"c":"d"}}""",
        )
        assertEquals("""{"a":[1,2.5,true,null],"b":{"c":"d"}}""", canonical)
    }

    // -- Truncation --------------------------------------------------------

    @Test
    fun `a long passage is cut to what the server takes`() {
        val long = "x".repeat(AnnotationWire.MAX_EXCERPT_BYTES + 500)
        val sent = JSONObject(AnnotationWire.item(mark(text = long), WORK, 0, null)!!.json)
        assertEquals(AnnotationWire.MAX_EXCERPT_BYTES, sent.getString("excerpt").toByteArray().size)
    }

    @Test
    fun `cutting never splits a character in half`() {
        // The cap counts bytes and an emoji is four of them, so a naive
        // cut lands mid-character and produces a string that is not
        // shorter so much as broken.
        val emoji = "🙂".repeat(100)
        val cut = AnnotationWire.truncate(emoji, 10)
        assertEquals(8, cut.toByteArray().size)
        assertEquals("🙂🙂", cut)
    }

    @Test
    fun `the reader keeps every word they wrote`() {
        val long = "x".repeat(AnnotationWire.MAX_BODY_BYTES + 10)
        val mark = mark(note = long)
        AnnotationWire.item(mark, WORK, 0, null)
        assertEquals(long, mark.note)
    }

    // -- Timestamps --------------------------------------------------------

    @Test
    fun `a stamp is written in UTC to the microsecond`() {
        assertEquals("2024-03-01T12:00:00.123456Z", AnnotationWire.clientTs(1_709_294_400_123_456))
    }

    @Test
    fun `a stamp read back is the stamp written`() {
        val micros = 1_709_294_400_123_456
        assertEquals(micros, AnnotationWire.micros(AnnotationWire.clientTs(micros)))
    }

    @Test
    fun `a finer stamp than this device keeps does not make a mark look edited`() {
        // The server's SQLite backend stores the text it was given, so a
        // client that sent nanoseconds gets nanoseconds back. Keeping
        // them would have every arriving record look changed, and be
        // pushed straight back.
        val record = AnnotationWire.record(server(clientTs = "2024-03-01T12:00:00.123456789Z"))!!
        assertEquals(1_709_294_400_123_456, record.clientTsMicros)

        val landed = AnnotationWire.toAnnotation(record, BOOK, null)
        val rebuilt = AnnotationWire.item(landed, WORK, record.rev, null)!!
        assertEquals(
            AnnotationWire.fingerprint(landed, WORK),
            rebuilt.fingerprint,
        )
    }

    // -- Reading what arrives ---------------------------------------------

    @Test
    fun `a record wearing a colour off the palette is refused`() {
        assertNull(AnnotationWire.record(server(color = "#c0ffee")))
    }

    @Test
    fun `a record claiming impossible progress is refused`() {
        assertNull(AnnotationWire.record(server(progression = 7.0)))
    }

    @Test
    fun `a record whose anchor will not parse is refused`() {
        assertNull(AnnotationWire.record(server(locator = "not json")))
    }

    @Test
    fun `a record of a kind this device has no word for is refused`() {
        assertNull(AnnotationWire.record(server(kind = "scribble")))
    }

    @Test
    fun `a standalone note is skipped rather than guessed at`() {
        // The server's own `note` is a body with no anchor. Liseur has
        // nowhere to hang one, and inventing an anchor would put the
        // reader's words at a place they never chose.
        val note = JSONObject(
            """{"id":"n","rev":1,"seq":1,"work_id":"$WORK","kind":"note","body":"standalone"}""",
        )
        assertNull(AnnotationWire.record(note))
    }

    @Test
    fun `a tombstone says only that the mark is gone`() {
        val record = AnnotationWire.record(
            JSONObject("""{"id":"m","rev":4,"seq":9,"deleted":true}"""),
        )!!
        assertTrue(record.deleted)
        assertEquals(4, record.rev)
    }

    @Test
    fun `a record with no rev is not a record`() {
        assertNull(AnnotationWire.record(JSONObject("""{"id":"m","rev":0}""")))
    }

    @Test
    fun `landing a record keeps what the server has no word for`() {
        // Which chapter a passage is in and which page it is on are read
        // out of the locator on the way in, because the wire does not
        // carry them and the notebook reads badly without them.
        val record = AnnotationWire.record(server())!!
        val landed = AnnotationWire.toAnnotation(record, BOOK, null)
        assertEquals("Chapter One", landed.chapter)
        assertEquals(12, landed.position)
        assertEquals(BOOK, landed.bookId)
    }

    // -- Recognising our own request --------------------------------------

    @Test
    fun `a request refused as somebody else's is recognised as ours`() {
        // A rotated token is a new device to the server, so a replayed
        // request is refused though every word of it is ours.
        val item = AnnotationWire.item(mark(), WORK, 0, null)!!
        val theirs = AnnotationWire.record(
            server(deviceId = "a-token-ago", color = "yellow", excerpt = "a passage"),
        )!!
        assertTrue(AnnotationWire.sameContent(item.json, theirs))
    }

    @Test
    fun `a genuinely different copy is not mistaken for ours`() {
        val item = AnnotationWire.item(mark(), WORK, 0, null)!!
        assertFalse(AnnotationWire.sameContent(item.json, AnnotationWire.record(server(color = "blue"))!!))
        assertFalse(AnnotationWire.sameContent(item.json, AnnotationWire.record(server(body = "added"))!!))
    }

    // -- Batches -----------------------------------------------------------

    @Test
    fun `a batch carries its items untouched`() {
        val one = AnnotationWire.item(mark(id = "a"), WORK, 0, null)!!
        val two = AnnotationWire.item(mark(id = "b"), WORK, 0, null)!!
        assertEquals(
            """{"annotations":[${one.json},${two.json}]}""",
            AnnotationWire.batchBody(listOf(one, two)),
        )
    }

    @Test
    fun `an id is data, not a path, whatever it spells`() {
        // The id is opaque to the server, so another client may
        // legitimately pick one that reads like a path. Refusing those
        // would make this client silently blind to marks the server is
        // perfectly happy to hold; they are carried, and the URL builder
        // is what has to keep them from meaning anything. Only an id no
        // amount of encoding makes addressable is turned away.
        for (awkward in listOf(".", "..", "a/b", "a\\b", "a?b", "a#b", "a b")) {
            assertNotNull(awkward, AnnotationWire.record(server(id = awkward)))
            assertNotNull(awkward, AnnotationWire.item(mark(id = awkward), WORK, 0, null))
        }
        for (bad in listOf("", "a\nb", "x".repeat(AnnotationWire.MAX_ID_BYTES + 1))) {
            assertNull(bad, AnnotationWire.record(server(id = bad)))
            assertNull(bad, AnnotationWire.item(mark(id = bad), WORK, 0, null))
        }
        assertNotNull(AnnotationWire.record(server(id = "8e0e-4f")))
    }

    @Test
    fun `a note too long for the server is offered whole, not quietly halved`() {
        // Truncating here would hash the short version as agreed while
        // the reader still has the long one, and the rest would be lost
        // with nothing anywhere reading as unsettled. Sent whole, the
        // server refuses it and the refusal is recorded.
        val long = "x".repeat(AnnotationWire.MAX_BODY_BYTES + 100)
        val item = AnnotationWire.item(mark(kind = AnnotationKind.NOTE, note = long), WORK, 0, null)!!
        assertEquals(long, JSONObject(item.json).getString("body"))
    }

    @Test
    fun `how far a page reaches is counted before anything is skipped`() {
        val readable = server(id = "a", seq = 3).toString()
        val standalone = JSONObject()
            .put("id", "b").put("rev", 1).put("seq", 11)
            .put("work_id", WORK).put("kind", "note").put("body", "a thought")
            .put("client_ts", STAMP).toString()
        val page = JSONArray("[$readable,$standalone]")

        assertEquals(1, AnnotationWire.records(page).size)
        assertEquals(11L, AnnotationWire.pageReach(page))
        assertNull(AnnotationWire.pageReach(JSONArray("[]")))
    }

    @Test
    fun `a cleared field arrives cleared`() {
        val existing = mark(text = "the whole passage", note = "a thought", tint = "YELLOW")
        val record = AnnotationWire.record(server(color = "", body = "", excerpt = ""))!!
        val landed = AnnotationWire.toAnnotation(record, BOOK, existing)

        assertNull(landed.tint)
        assertNull(landed.note)
        assertNull(landed.text)
    }

    @Test
    fun `an excerpt this device truncated on the way out keeps its full passage`() {
        val whole = "a".repeat(AnnotationWire.MAX_EXCERPT_BYTES + 50)
        val sent = AnnotationWire.truncate(whole, AnnotationWire.MAX_EXCERPT_BYTES)
        val existing = mark(text = whole)
        val landed = AnnotationWire.toAnnotation(
            AnnotationWire.record(server(excerpt = sent))!!,
            BOOK,
            existing,
        )

        // Restoring precision this device threw away, not ignoring an
        // edit: what came back is a prefix of what is here.
        assertEquals(whole, landed.text)
    }

    private companion object {
        const val WORK = "work-1"
        const val BOOK = "file:///books/one.epub"
        const val LOCATOR =
            """{"href":"/c1.xhtml","locations":{"position":12},"title":"Chapter One"}"""
        const val STAMP = "2024-03-01T12:00:00.123456Z"
        const val MICROS = 1_709_294_400_123_456L

        fun mark(
            id: String = "mark-1",
            kind: AnnotationKind = AnnotationKind.HIGHLIGHT,
            locator: String = LOCATOR,
            text: String? = "a passage",
            note: String? = null,
            tint: String? = "YELLOW",
            updatedAt: Long = MICROS,
        ) = BookAnnotation(
            id = id,
            bookId = BOOK,
            kind = kind.name,
            locatorJson = locator,
            text = text,
            note = note,
            tint = tint,
            chapter = "Chapter One",
            position = 12,
            totalProgression = 0.25,
            createdAt = 1_709_294_400_000,
            updatedAt = updatedAt,
        )

        fun server(
            id: String = "mark-1",
            rev: Long = 1,
            seq: Long = rev,
            kind: String = "highlight",
            locator: String = LOCATOR,
            excerpt: String = "a passage",
            body: String = "",
            color: String = "yellow",
            progression: Double = 0.25,
            deviceId: String = "this-device",
            clientTs: String = STAMP,
        ): JSONObject {
            val locatorValue = runCatching { JSONObject(locator) }.getOrNull() ?: locator
            return JSONObject()
                .put("id", id)
                .put("rev", rev)
                .put("seq", seq)
                .put("work_id", WORK)
                .put("kind", kind)
                .put("locator", locatorValue)
                .put("excerpt", excerpt)
                .put("body", body)
                .put("color", color)
                .put("progression", progression)
                .put("device_id", deviceId)
                .put("client_ts", clientTs)
        }
    }
}
