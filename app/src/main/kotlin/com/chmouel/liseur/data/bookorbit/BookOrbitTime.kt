package com.chmouel.liseur.data.bookorbit

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Timestamps as BookOrbit writes them.
 *
 * BookOrbit is a Node service talking to Postgres, and its timestamps
 * come back as ISO-8601 instants with an offset, usually `Z` and
 * occasionally the server's own offset. A timestamp with no offset at
 * all is not something it has been seen to produce, but it is read as
 * UTC rather than refused, because the alternative is losing a reading
 * time to a formatting difference.
 */
object BookOrbitTime {

    /** Milliseconds since the epoch, or null if the value is not a time. */
    fun parse(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * How BookOrbit wants a time sent to it.
     *
     * The session endpoints take any `@IsDateString`, and the progress
     * endpoints take anything with an offset, so the canonical UTC
     * spelling with milliseconds is the one to write. A date-only value
     * is deliberately never produced: these are reading times, not
     * calendar days.
     */
    fun format(epochMillis: Long): String =
        FORMAT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
}
