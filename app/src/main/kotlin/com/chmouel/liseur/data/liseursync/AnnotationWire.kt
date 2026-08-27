package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Turns a mark the reader made into what liseur-sync stores, and back.
 *
 * Deliberately free of Android types so the rules that matter — what
 * counts as the same payload, where a string is cut, how a timestamp is
 * written — can be tested without a device or Robolectric.
 *
 * The server (ADR-0028) recognises a repeated write only when the whole
 * payload matches byte for byte, so almost everything here is about
 * being boringly deterministic: sorted keys, a fixed timestamp format,
 * one canonical spelling per value.
 */
object AnnotationWire {

    /** What the server will take, from `docs/openapi.yaml`. */
    val COLORS = setOf("yellow", "green", "blue", "pink", "purple", "orange")

    const val KIND_HIGHLIGHT = "highlight"
    const val KIND_NOTE = "note"
    const val KIND_BOOKMARK = "bookmark"

    const val MAX_EXCERPT_BYTES = 1 shl 10
    const val MAX_BODY_BYTES = 16 shl 10
    const val MAX_BATCH = 100
    const val MAX_ID_BYTES = 64

    /**
     * One annotation as a request item, ready to be sent and kept.
     *
     * [json] is the exact text that goes on the wire: it is stored and
     * replayed rather than rebuilt, because rebuilding is what makes a
     * retry stop being a retry.
     */
    data class Item(
        val id: String,
        val workId: String,
        val baseRev: Long,
        val fingerprint: String,
        val json: String,
    )

    /** A record as the server sent it back. */
    data class Record(
        val id: String,
        val rev: Long,
        val seq: Long,
        val workId: String?,
        val editionSha: String?,
        val kind: String?,
        val locator: String?,
        val progression: Double?,
        val excerpt: String,
        val color: String,
        val body: String,
        val deviceId: String,
        val clientTsMicros: Long,
        val deleted: Boolean,
    )

    // -- Outbound ---------------------------------------------------------

    /**
     * Builds the request item for a mark, or null when it is not
     * something this server can hold.
     *
     * A highlight and a bookmark have to anchor to the text, while a
     * book note deliberately has no anchor. A locator that cannot be
     * read back is not an anchor. Refusing here rather than letting the
     * server refuse keeps a broken row from being offered on every run
     * for ever.
     */
    fun item(
        annotation: BookAnnotation,
        workId: String,
        baseRev: Long,
        editionSha: String?,
    ): Item? {
        val kind = wireKind(annotation) ?: return null
        val locator = if (kind == KIND_NOTE) null else canonicalLocator(annotation.locatorJson)
            ?: return null
        if (!usableId(annotation.id)) return null

        // Sent whole. Truncating here would hash the short version as
        // agreed while the reader still has the long one, so the rest
        // would be lost with nothing anywhere reading as unsettled. Over
        // the limit the server refuses it, which is recorded against the
        // fingerprint and shown in a log — a stalled mark rather than a
        // silently halved one.
        val body = when (kind) {
            KIND_BOOKMARK -> ""
            else -> annotation.note.orEmpty()
        }
        if (kind == KIND_NOTE && body.isEmpty()) return null
        val color = when (kind) {
            KIND_HIGHLIGHT -> color(annotation.tint)
            else -> ""
        }

        // Assembled by hand rather than through JSONObject: its key
        // order comes out of a hash map, and an order that shifts
        // between two runs is a payload the server reads as different.
        val fields = buildList {
            add("\"id\":" + quote(annotation.id))
            add("\"base_rev\":$baseRev")
            add("\"work_id\":" + quote(workId))
            if (editionSha != null && kind != KIND_NOTE) {
                add("\"edition_sha\":" + quote(editionSha))
            }
            add("\"kind\":" + quote(kind))
            locator?.let { add("\"locator\":$it") }
            if (kind != KIND_NOTE) {
                annotation.totalProgression
                    ?.takeIf { it in 0.0..1.0 }
                    ?.let { add("\"progression\":${number(it)}") }
                truncate(annotation.text.orEmpty(), MAX_EXCERPT_BYTES)
                    .takeIf { it.isNotEmpty() }
                    ?.let { add("\"excerpt\":" + quote(it)) }
            }
            if (color.isNotEmpty()) add("\"color\":" + quote(color))
            if (body.isNotEmpty()) add("\"body\":" + quote(body))
            add("\"client_ts\":" + quote(clientTs(annotation.updatedAt)))
        }

        return Item(
            id = annotation.id,
            workId = workId,
            baseRev = baseRev,
            fingerprint = fingerprint(annotation, workId),
            json = fields.joinToString(",", "{", "}"),
        )
    }

    /** Wraps items into a push body without reparsing any of them. */
    fun batchBody(items: List<Item>): String = batchBodyOf(items.map { it.json })

    /** The same envelope, around items read back out of the database. */
    fun batchBodyOf(jsons: List<String>): String =
        jsons.joinToString(",", "{\"annotations\":[", "]}")

    /**
     * What the server has of a mark, as one string.
     *
     * Content only. The base rev is left out on purpose: it changes with
     * every accepted write, and folding it in would leave a row looking
     * edited the instant it was acknowledged. So is `edition_sha`, for a
     * subtler reason — two devices holding different files of the same
     * book each name a different edition, and a hash that noticed would
     * have them overwrite each other's reference on every run, for ever,
     * over something neither reader can see. It still goes on the wire,
     * where it is a hint about which file was marked; it just never
     * makes a mark look changed on its own.
     */
    fun fingerprint(annotation: BookAnnotation, workId: String): String {
        val kind = wireKind(annotation)
        val anchored = kind != KIND_NOTE
        val parts = listOf(
            workId,
            kind.orEmpty(),
            if (anchored) canonicalLocator(annotation.locatorJson).orEmpty() else "",
            if (anchored) {
                annotation.totalProgression?.takeIf { it in 0.0..1.0 }?.let(::number).orEmpty()
            } else {
                ""
            },
            if (anchored) truncate(annotation.text.orEmpty(), MAX_EXCERPT_BYTES) else "",
            if (kind == KIND_HIGHLIGHT) color(annotation.tint) else "",
            if (kind == KIND_BOOKMARK) "" else annotation.note.orEmpty(),
            clientTs(annotation.updatedAt),
        )
        return sha256(parts.joinToString("\u0000"))
    }

    /**
     * The kind the server would call this mark, or null if it would call
     * it nothing.
     *
     * A passage note is what the server calls a highlight carrying a body;
     * a book note maps to its anchorless `note` kind.
     */
    fun wireKind(annotation: BookAnnotation): String? = when (annotation.kind) {
        AnnotationKind.HIGHLIGHT.name, AnnotationKind.NOTE.name -> KIND_HIGHLIGHT
        AnnotationKind.BOOK_NOTE.name -> KIND_NOTE
        AnnotationKind.BOOKMARK.name -> KIND_BOOKMARK
        else -> null
    }

    private fun color(tint: String?): String {
        val lower = tint?.lowercase(Locale.ROOT).orEmpty()
        return if (lower in COLORS) lower else ""
    }

    /**
     * Whether an id is one this device can hold at all.
     *
     * Only the things no encoding can rescue: nothing, more than the
     * server itself would take, or a control character, which has no
     * representation in a header or a log and no business in a database
     * key. Everything else an id may be — `.`, `..`, a slash, a question
     * mark — is opaque data the server is entitled to hand out, and
     * refusing those here would make another client's perfectly ordinary
     * annotation permanently unsupported. Addressing them safely is the
     * URL builder's job, not this one's.
     */
    fun usableId(id: String): Boolean = id.isNotEmpty() &&
        id.none { it.isISOControl() } &&
        id.toByteArray().size <= MAX_ID_BYTES

    // -- Inbound ----------------------------------------------------------

    /**
     * Reads a record from the feed, or null if it is not one this device
     * can trust or represent.
     *
     * Checked as though it were hostile, because a database is not the
     * place to find out that a colour was a stylesheet.
     */
    fun record(json: JSONObject): Record? {
        val id = json.optString("id")
        if (!usableId(id)) return null
        val rev = json.optLong("rev")
        if (rev < 1) return null
        // Every record the server holds has a seq, and it is the clock
        // this whole pass tells freshness by. A missing one would read
        // as 0, which is not "unknown" but "older than everything" —
        // the record would land once and then be judged stale for ever.
        val seq = json.optLong("seq")
        if (seq < 1) return null

        if (json.optBoolean("deleted")) {
            return Record(
                id = id, rev = rev, seq = seq, workId = null, editionSha = null,
                kind = null, locator = null, progression = null, excerpt = "",
                color = "", body = "", deviceId = "", clientTsMicros = 0, deleted = true,
            )
        }

        val kind = json.optString("kind")
        if (kind != KIND_HIGHLIGHT && kind != KIND_NOTE && kind != KIND_BOOKMARK) return null
        val workId = json.optString("work_id").takeIf { it.isNotEmpty() } ?: return null

        val locator = anchor(json.opt("locator"))
        if (kind == KIND_NOTE) {
            if (locator != null) return null
        } else if (locator == null) {
            return null
        }

        val progression = if (json.isNull("progression")) null else json.optDouble("progression")
        if (progression != null && (progression.isNaN() || progression !in 0.0..1.0)) return null

        val color = json.optString("color")
        if (color.isNotEmpty() && color !in COLORS) return null
        if (color.isNotEmpty() && kind != KIND_HIGHLIGHT) return null

        val excerpt = json.optString("excerpt")
        val body = json.optString("body")
        if (excerpt.toByteArray().size > MAX_EXCERPT_BYTES) return null
        if (body.toByteArray().size > MAX_BODY_BYTES) return null
        if (body.isNotEmpty() && kind == KIND_BOOKMARK) return null
        if (body.isEmpty() && kind == KIND_NOTE) return null

        return Record(
            id = id,
            rev = rev,
            seq = seq,
            workId = workId,
            editionSha = json.optString("edition_sha").takeIf { it.isNotEmpty() },
            kind = kind,
            locator = locator,
            progression = progression,
            excerpt = excerpt,
            color = color,
            body = body,
            deviceId = json.optString("device_id"),
            clientTsMicros = micros(json.optString("client_ts")),
            deleted = false,
        )
    }

    /** Every record in a feed page, skipping the ones that will not do. */
    fun records(array: JSONArray?): List<Record> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::record)
        }
    }

    /**
     * Every id in a live set, filtered by nothing.
     *
     * What a work holds is a question about ids, and the answer must not
     * depend on which of them this device could represent. The size
     * limits here are the server's *defaults*; an administrator may have
     * raised them, and a record over the local limit is skipped. Asked
     * through the readable records only, such a record would read as
     * absent — and absence is what removes a mark.
     */
    fun ids(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until array.length())
            .mapNotNullTo(mutableSetOf()) { array.optJSONObject(it)?.optString("id") }
    }

    /**
     * How far a page of the feed reaches, or null if it says nothing.
     *
     * Counted before anything is skipped: the cursor has to move by
     * what the server sent, not by what this device could read. Taking
     * the account's high water mark for a page of unknown records would
     * step over every readable mark after them, silently, once, for ever.
     * A seq below 1 is not a reach either — `optLong` reads a missing or
     * unparseable one as 0, and treating that as an answer would pin the
     * cursor just as firmly. Null hands the page back to `high_water`,
     * which is only for a page that really was empty.
     */
    fun pageReach(array: JSONArray?): Long? {
        if (array == null || array.length() == 0) return null
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optLong("seq")?.takeIf { seq -> seq >= 1 } }
            .maxOrNull()
    }

    /**
     * Whether the server's copy is, field for field, the request this
     * device sent.
     *
     * Asked after a conflict, and it has one real cause. The server
     * stamps a write with the device the *token* belongs to, and a
     * rotated token is a new device — so a request replayed across a
     * rotation is refused as somebody else's, though every word of it is
     * ours. Recognising that costs one comparison and saves the reader's
     * edit from being overwritten by a copy of itself.
     *
     * Reparsing [pendingJson] here is safe in a way that reparsing it to
     * *send* would not be: nothing that comes out of this goes back on
     * the wire.
     */
    fun sameContent(pendingJson: String?, record: Record): Boolean {
        if (pendingJson == null || record.deleted) return false
        val sent = runCatching { JSONObject(pendingJson) }.getOrNull() ?: return false
        if (sent.optString("kind") != record.kind) return false
        if (sent.optString("work_id") != record.workId) return false
        if (canonicalLocator(sent.opt("locator")?.toString()) != canonicalLocator(record.locator)) {
            return false
        }
        if (sent.optString("excerpt") != record.excerpt) return false
        if (sent.optString("color") != record.color) return false
        if (sent.optString("body") != record.body) return false
        if (micros(sent.optString("client_ts")) != record.clientTsMicros) return false
        val sentProgression = if (sent.has("progression")) sent.optDouble("progression") else null
        return sentProgression == record.progression
    }

    /**
     * Lays a record over the mark this device keeps.
     *
     * [existing] is kept for what the server has no word for — which
     * chapter a passage is in, which page it is on. Those are read back
     * out of the locator when there is nothing to inherit.
     */
    fun toAnnotation(record: Record, bookId: String, existing: BookAnnotation?): BookAnnotation {
        val bookNote = record.kind == KIND_NOTE
        val locator = record.locator?.let { runCatching { JSONObject(it) }.getOrNull() }
        val locations = locator?.optJSONObject("locations")
        val kind = when {
            bookNote -> AnnotationKind.BOOK_NOTE
            record.kind == KIND_BOOKMARK -> AnnotationKind.BOOKMARK
            record.body.isNotEmpty() -> AnnotationKind.NOTE
            else -> AnnotationKind.HIGHLIGHT
        }
        return BookAnnotation(
            id = record.id,
            bookId = bookId,
            kind = kind.name,
            locatorJson = record.locator.orEmpty(),
            // Everything the protocol carries is taken as it arrives,
            // cleared values included: a field that falls back to what
            // was here before makes a clear made on another device
            // invisible, and the two copies then disagree for ever with
            // nothing anywhere reading as unsettled.
            //
            // The excerpt is the one place a local value survives, and
            // only when the server's is a prefix of it. That is this
            // device's own doing — an excerpt goes out truncated to a
            // kilobyte — so keeping the whole passage is restoring
            // precision, not ignoring an edit.
            text = record.excerpt.takeIf { !bookNote && it.isNotEmpty() }?.let { excerpt ->
                existing?.text
                    ?.takeIf { truncate(it, MAX_EXCERPT_BYTES) == excerpt }
                    ?: excerpt
            },
            note = record.body.takeIf { it.isNotEmpty() },
            tint = record.color.takeIf { !bookNote && it.isNotEmpty() }?.uppercase(Locale.ROOT),
            chapter = if (bookNote) {
                null
            } else {
                existing?.chapter ?: locator?.optString("title")?.takeIf { it.isNotEmpty() }
            },
            position = if (bookNote) {
                null
            } else {
                existing?.position ?: locations?.optInt("position")?.takeIf { it > 0 }
            },
            totalProgression = if (bookNote) null else record.progression,
            createdAt = existing?.createdAt ?: (record.clientTsMicros / 1000),
            updatedAt = record.clientTsMicros,
        )
    }

    // -- Shapes -----------------------------------------------------------

    /**
     * The anchor a record actually carries, or null when it carries none.
     *
     * A peer with nothing to anchor may say so as an absent key, a JSON
     * null, an empty string or an empty object, and they all mean the
     * same thing — `SyncOps.locatorFor` has read them that way all
     * along. Reading one of those spellings as a locator would refuse a
     * standalone note for ever, since the record is skipped on every
     * pull and every reconcile alike, and would let an anchored mark
     * through with an anchor that points at nothing.
     */
    fun anchor(value: Any?): String? {
        val raw = value?.takeIf { it != JSONObject.NULL }?.toString() ?: return null
        val canonical = canonicalLocator(raw) ?: return null
        return raw.takeIf { canonical != "{}" }
    }

    /**
     * A locator written one way and one way only, with its keys in
     * order, or null if it is not JSON at all.
     *
     * The bytes are what the server compares, so they are settled once,
     * here, and then carried around rather than regenerated.
     */
    fun canonicalLocator(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return canonical(parsed)
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            quote(key) + ":" + canonical(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        // Every other numeric type through one spelling. Two `org.json`
        // implementations do not agree on what to parse `2.5` into —
        // Double here, BigDecimal there — and a locator that reads
        // differently on two runtimes is a payload that never matches
        // itself.
        is Number -> number(value.toDouble())
        else -> quote(value.toString())
    }

    /**
     * A number without a trailing `.0` where it is really an integer, so
     * the same value does not have two spellings.
     */
    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    // -- Bounds -----------------------------------------------------------

    /**
     * Cuts a string to fit the server's limit, which counts bytes.
     *
     * Cut on a character boundary, and never through a surrogate pair:
     * half an emoji is not a shorter excerpt, it is a broken one.
     */
    fun truncate(value: String, maxBytes: Int): String {
        if (value.isEmpty()) return value
        val bytes = value.toByteArray()
        if (bytes.size <= maxBytes) return value

        var end = 0
        var used = 0
        while (end < value.length) {
            val point = value.codePointAt(end)
            val width = Character.charCount(point)
            val size = String(Character.toChars(point)).toByteArray().size
            if (used + size > maxBytes) break
            used += size
            end += width
        }
        return value.substring(0, end)
    }

    // -- Time -------------------------------------------------------------

    /**
     * A timestamp the server will read back as the one it stored.
     *
     * Microseconds, always UTC, always six decimal places: Postgres
     * keeps `client_ts` to the microsecond and the retry check compares
     * it there, so a finer stamp would be a payload that never matches
     * itself.
     */
    fun clientTs(micros: Long): String {
        val seconds = Math.floorDiv(micros, 1_000_000L)
        val fraction = Math.floorMod(micros, 1_000_000L)
        val instant = java.time.Instant.ofEpochSecond(seconds)
        val date = instant.atOffset(java.time.ZoneOffset.UTC)
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02d.%06dZ",
            date.year, date.monthValue, date.dayOfMonth,
            date.hour, date.minute, date.second, fraction,
        )
    }

    /**
     * Reads a stamp back, keeping microseconds and no more.
     *
     * The server's SQLite backend stores the text it was given, which
     * can carry nanoseconds another client sent. Anything finer than a
     * microsecond is dropped rather than stored, because the local
     * column cannot hold it and a value that changes on the way in is a
     * record that looks edited the moment it lands.
     */
    fun micros(text: String?): Long {
        if (text.isNullOrEmpty()) return 0
        val parsed = runCatching { java.time.Instant.parse(text) }.getOrNull() ?: return 0
        return parsed.epochSecond * 1_000_000L + parsed.nano / 1_000L
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
