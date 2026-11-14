package com.chmouel.liseur.domain

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader

data class BackedUpSession(
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val idleMs: Long?,
    val startProgression: Double?,
    val endProgression: Double?,
)

data class BackedUpBookSessions(
    val bookId: String,
    val title: String?,
    val author: String?,
    val sessions: List<BackedUpSession>,
)

fun decodeSessionBackup(input: Reader): List<BackedUpBookSessions> = JsonReader(input).use { reader ->
    val books = mutableListOf<BackedUpBookSessions>()
    val ids = mutableSetOf<String>()
    var format = false
    var application = false
    var hasBooks = false
    var totalSessions = 0
    reader.beginObject()
    val fields = mutableSetOf<String>()
    while (reader.hasNext()) {
        val name = reader.nextName()
        require(fields.add(name))
        when (name) {
            "format" -> { require(reader.nonNegativeLong() == 1L); format = true }
            "application" -> { require(reader.text() == "liseur"); application = true }
            "books" -> {
                hasBooks = true
                reader.beginArray()
                while (reader.hasNext()) {
                    require(books.size < MAX_SESSION_BOOKS)
                    val book = reader.bookSessions()
                    require(ids.add(book.bookId))
                    totalSessions += book.sessions.size
                    require(totalSessions <= MAX_SESSIONS)
                    books += book
                }
                reader.endArray()
            }
            else -> error("Unknown session backup field")
        }
    }
    reader.endObject()
    require(format && application && hasBooks && reader.peek() == JsonToken.END_DOCUMENT)
    books
}

private fun JsonReader.bookSessions(): BackedUpBookSessions {
    var bookId: String? = null
    var title: String? = null
    var author: String? = null
    var sessions: List<BackedUpSession>? = null
    val fields = mutableSetOf<String>()
    beginObject()
    while (hasNext()) {
        val field = nextName()
        require(fields.add(field))
        when (field) {
            "book_id" -> bookId = text()
            "title" -> title = nullableText()
            "author" -> author = nullableText()
            "sessions" -> {
                val values = mutableListOf<BackedUpSession>()
                beginArray()
                while (hasNext()) {
                    require(values.size < MAX_SESSIONS)
                    values += session()
                }
                endArray()
                sessions = values
            }
            else -> error("Unknown session book field")
        }
    }
    endObject()
    return BackedUpBookSessions(
        bookId = requireNotNull(bookId),
        title = title,
        author = author,
        sessions = requireNotNull(sessions),
    )
}

private fun JsonReader.session(): BackedUpSession {
    var startedAt: Long? = null
    var endedAt: Long? = null
    var durationMs: Long? = null
    var idleMs: Long? = null
    var startProgression: Double? = null
    var endProgression: Double? = null
    val fields = mutableSetOf<String>()
    beginObject()
    while (hasNext()) {
        val field = nextName()
        require(fields.add(field))
        when (field) {
            "started_at" -> startedAt = nonNegativeLong()
            "ended_at" -> endedAt = nonNegativeLong()
            "duration_ms" -> durationMs = nonNegativeLong()
            "idle_ms" -> idleMs = nullableNonNegativeLong()
            "start_progression" -> startProgression = nullableProgression()
            "end_progression" -> endProgression = nullableProgression()
            else -> error("Unknown session field")
        }
    }
    endObject()
    val start = requireNotNull(startedAt)
    val end = requireNotNull(endedAt)
    val duration = requireNotNull(durationMs)
    require(end >= start)
    // Reading totals are summed in SQLite, so one archive must not be able to overflow them.
    require(duration <= MAX_SESSION_DURATION_MS)
    require(idleMs == null || idleMs <= MAX_SESSION_IDLE_MS)
    return BackedUpSession(start, end, duration, idleMs, startProgression, endProgression)
}

private fun JsonReader.text(limit: Int = 64 * 1024): String {
    require(peek() == JsonToken.STRING)
    return nextString().also { require(it.isNotBlank() && it.length <= limit) }
}

private fun JsonReader.nullableText(): String? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else {
        require(peek() == JsonToken.STRING)
        nextString().also { require(it.length <= 64 * 1024) }
    }

private fun JsonReader.nonNegativeLong(): Long {
    require(peek() == JsonToken.NUMBER)
    return nextString().also { require(it.matches(NON_NEGATIVE_INT)) }.toLong()
}

private fun JsonReader.nullableNonNegativeLong(): Long? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else nonNegativeLong()

private fun JsonReader.nullableProgression(): Double? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else {
        require(peek() == JsonToken.NUMBER)
        nextDouble().also { require(it.isFinite() && it in 0.0..1.0) }
    }

private val NON_NEGATIVE_INT = Regex("0|[1-9][0-9]*")

const val MAX_SESSION_BOOKS = 20_000
const val MAX_SESSIONS = 1_000_000

/**
 * The largest duration one archive may declare and still sum safely.
 *
 * Not a statement about honest sittings: with [MAX_SESSIONS] of them, the total
 * stays under a quarter of a Long, so SQLite and Kotlin can add them without
 * overflow. That leaves about 73 years per session, longer than any recording,
 * so the exporter never has to drop a real one.
 */
const val MAX_SESSION_DURATION_MS = Long.MAX_VALUE / (4L * MAX_SESSIONS)

/** Idle time is bounded by the same arithmetic-safety budget as duration. */
const val MAX_SESSION_IDLE_MS = MAX_SESSION_DURATION_MS
