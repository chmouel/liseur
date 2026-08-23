package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSession
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

/**
 * Turning a stretch of reading into something the server will accept.
 *
 * The same bargain as [SyncOps]: `session_id` is an idempotency key and
 * the server compares the payload behind it, so the id is derived from
 * the session rather than drawn, and every field comes from the stored
 * row. A batch sent twice because the answer was lost is recognised as
 * a duplicate rather than counted twice, which matters more here than
 * for positions — a position sent twice is the same position, but an
 * hour sent twice is an hour that never happened.
 *
 * Only *closed* sessions are ever sent, which is what makes the payload
 * stable enough for that to work.
 */
object SessionUploads {

    /** The most sessions the server will take in one request. */
    const val MAX_BATCH = 1000

    fun sessionIdFor(deviceKey: String, localId: Long): String =
        UUID.nameUUIDFromBytes(
            "$deviceKey|session|$localId".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    fun toJson(
        session: ReadingSession,
        deviceKey: String,
        workId: String,
        editionSha: String?,
    ): JSONObject? {
        val started = session.startedAt
        val ended = session.endedAt ?: return null
        val start = session.startProgression ?: return null
        val end = session.endProgression ?: return null
        return JSONObject().apply {
            put("session_id", sessionIdFor(deviceKey, session.id))
            put("work_id", workId)
            editionSha?.let { put("edition_sha", it) }
            put("started_at", SyncOps.formatTime(started))
            put("ended_at", SyncOps.formatTime(maxOf(ended, started)))
            put("start_progression", start.coerceIn(0.0, 1.0))
            put("end_progression", end.coerceIn(0.0, 1.0))
            // The gap between how long the session lasted and how much
            // of it was reading.
            //
            // Reading time is counted only while the reader is in the
            // foreground, off a monotonic clock, but a session is
            // bounded by wall-clock moments — so a book left open while
            // the reader answered the door spans more time than it
            // counted. The server works its own active time out as the
            // span minus this, and without it would credit the doorstep
            // conversation as reading and report a slower pace than the
            // device that measured it.
            //
            // The figure is read from the row rather than worked out
            // here. A session id is derived from what it carries, so a
            // payload that changed between one attempt and the next
            // would reach the server as the same id saying something
            // else — refused, and the whole batch with it. A sitting
            // recorded before the column existed has no figure and
            // sends the nought it would have sent then.
            //
            // What this app still cannot tell is a difficult page from a
            // book left open on the sofa with the screen on, and
            // guessing at that would be worse than admitting it.
            put("idle_ms", session.idleMs ?: 0L)
        }
    }
}
