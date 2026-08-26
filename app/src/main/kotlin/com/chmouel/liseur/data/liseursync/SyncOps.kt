package com.chmouel.liseur.data.liseursync

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

/** A position, as liseur-sync exchanges it. */
data class SyncOp(
    val opId: String,
    val workId: String,
    val editionSha: String?,
    val clientTs: Long,
    val progression: Double,
    val locatorJson: String?,
    val deviceId: String? = null,
    val seq: Long = 0,
)

/**
 * One record from the server's feed: a sequence number, and a position
 * only if the record made sense.
 *
 * The two are separate because they are owed to different people. The
 * cursor is owed the `seq` of every record the server sent, whether or
 * not this device could read it — dropping one either strands the feed
 * on a page it re-fetches forever, or hands the cursor to `high_water`,
 * which is the account's global maximum and would skip every real
 * position after it.
 *
 * Reading state, on the other hand, is owed nothing by a record that
 * could not be read. A position that arrives unreadable must not be
 * landed as a position of zero, and must not be landed as no position
 * either: `readingStatusFor(null)` is `ReadyToRead`, so letting one
 * through would mark a book the reader is part-way through as unread.
 */
data class SyncFeedItem(
    val seq: Long,
    val op: SyncOp?,
)

/**
 * Turning a reading position into an op, and back.
 *
 * The interesting part is [opIdFor]. The server treats `op_id` as an
 * idempotency key and compares the whole payload behind it: the same id
 * with the same payload is a harmless duplicate, the same id with a
 * different payload is a refusal. That gives retries for free — but only
 * if the same position always produces the same op, byte for byte.
 *
 * So the id is derived from what is being sent rather than drawn at
 * random, and every field of the payload comes from stored state rather
 * than from the clock. A push interrupted by a dead network is then
 * simply repeated on the next run: the server recognises it and says
 * `duplicate`, which is as good as `applied`. Nothing has to be written
 * down about requests in flight, which is the failure mode that would
 * otherwise need its own table and its own way of going wrong.
 */
object SyncOps {

    /** The server refuses a locator larger than this. */
    const val MAX_LOCATOR_BYTES = 16 * 1024

    /** The most ops the server will take in one request. */
    const val MAX_BATCH = 500

    /**
     * A name for this position that this device will always agree with
     * itself about.
     *
     * A UUID, because that is what the field is documented to carry, but
     * a derived one: the same book at the same revision on the same
     * device is always the same op. The device is in the name because
     * two phones at the same revision of the same book are two genuinely
     * different positions and must not silence each other.
     */
    fun opIdFor(deviceKey: String, workId: String, revision: Long): String =
        UUID.nameUUIDFromBytes(
            "$deviceKey|$workId|$revision".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    fun toJson(op: SyncOp): JSONObject = JSONObject().apply {
        put("op_id", op.opId)
        put("work_id", op.workId)
        op.editionSha?.let { put("edition_sha", it) }
        put("client_ts", formatTime(op.clientTs))
        put("progression", op.progression)
        locatorFor(op.locatorJson)?.let { put("locator", it) }
    }

    /**
     * The locator to send, or null when it is too big to send.
     *
     * A locator that will not fit is dropped and the progression goes on
     * its own. Failing the whole push would leave the other device with
     * no idea where the reader is, which is far worse than reopening the
     * book at a percentage instead of on the exact word.
     */
    fun locatorFor(locatorJson: String?): JSONObject? {
        if (locatorJson.isNullOrEmpty() || locatorJson == "{}") return null
        if (locatorJson.toByteArray(StandardCharsets.UTF_8).size > MAX_LOCATOR_BYTES) return null
        return runCatching { JSONObject(locatorJson) }.getOrNull()
    }

    fun fromJson(json: JSONObject): SyncOp? {
        val workId = json.optString("work_id").takeIf { it.isNotEmpty() } ?: return null
        val opId = json.optString("op_id").takeIf { it.isNotEmpty() } ?: return null
        val progression = (fractionAt(json, "progression") as? Fraction.Present)?.value
            ?: return null
        val locator = json.optJSONObject("locator")
        if (locator != null && !locatorReadable(locator)) return null
        return SyncOp(
            opId = opId,
            workId = workId,
            editionSha = json.optString("edition_sha").takeIf { it.isNotEmpty() },
            clientTs = parseTime(json.optString("client_ts")) ?: 0,
            progression = progression,
            locatorJson = locator?.toString(),
            deviceId = json.optString("device_id").takeIf { it.isNotEmpty() },
            seq = json.optLong("seq"),
        )
    }

    /**
     * A record from the feed, kept for its sequence number even when its
     * position is unusable.
     *
     * Null only when there is nothing to keep at all: no readable
     * position *and* no sequence number to advance the cursor with.
     */
    fun feedItemFrom(json: JSONObject): SyncFeedItem? {
        val op = fromJson(json)
        val seq = op?.seq ?: json.optLong("seq")
        if (op == null && seq <= 0) return null
        return SyncFeedItem(seq = seq, op = op)
    }

    /**
     * A number that was really there, told apart from one that was not.
     *
     * `optDouble(key, 0.0)` is the mistake this exists to avoid: it
     * turns a null, a string and a missing key alike into a
     * legitimate-looking start of the book. An unpatched or third-party
     * server that writes `"progression": null` — which is what
     * `JSON.stringify` does with a `NaN` — must not be able to send this
     * reader back to page one.
     */
    private fun fractionAt(json: JSONObject, key: String): Fraction {
        if (!json.has(key)) return Fraction.Absent
        if (json.isNull(key)) return Fraction.Unusable
        val fraction = when (val value = json.opt(key)) {
            is Number -> value.toDouble()
            // A server that quotes its numbers is out of contract but
            // not ambiguous, and the value is checked either way. This
            // is also the one door a non-finite can still come through:
            // org.json refuses a bare `NaN` while parsing, but "NaN" is
            // just a string until something asks it for a double.
            is String -> value.toDoubleOrNull() ?: return Fraction.Unusable
            else -> return Fraction.Unusable
        }
        // Written this way round so a NaN is refused: it answers false
        // to both comparisons, and so would also answer false to the
        // `< 0 || > 1` phrasing that reads more naturally.
        if (!(fraction >= 0.0 && fraction <= 1.0)) return Fraction.Unusable
        return Fraction.Present(fraction)
    }

    /**
     * Whether a locator's own idea of the position is usable.
     *
     * Only explicit malformedness is refused. A locator with no
     * `locations`, or a partner that syncs a percentage and sends no
     * locator at all, are both legitimate shapes and are left alone.
     */
    private fun locatorReadable(locator: JSONObject): Boolean {
        val locations = locator.optJSONObject("locations") ?: return true
        return fractionAt(locations, "totalProgression") !is Fraction.Unusable
    }

    private sealed interface Fraction {
        data class Present(val value: Double) : Fraction
        data object Absent : Fraction
        data object Unusable : Fraction
    }

    /**
     * RFC 3339, in UTC.
     *
     * Always UTC, never the phone's zone: the timestamp is part of the
     * payload the server compares, so a device that travels must not
     * start describing the same position differently.
     */
    fun formatTime(millis: Long): String = formatter().format(Date(millis))

    fun parseTime(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        runCatching { return Instant.parse(text).toEpochMilli() }
        // Fractional seconds are optional on the wire, and the server
        // trims trailing zeroes, so both shapes have to parse.
        for (pattern in TIME_PATTERNS) {
            runCatching {
                return formatter(pattern).parse(text)?.time
            }
        }
        return null
    }

    private fun formatter(pattern: String = TIME_PATTERNS.first()) =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )
}
