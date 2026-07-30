package com.chmouel.liseur.data.komga

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Timestamps as Komga writes and reads them.
 *
 * Komga is inconsistent about the offset, and both spellings turn up in
 * the same session: a book's `lastModified` comes back as UTC with a
 * `Z`, while a reading position written a moment ago comes back with
 * the server's local offset. A parser that only knew one of them would
 * work until the clocks changed.
 */
object KomgaTime {

    /**
     * Milliseconds since the epoch, or null if the value is not a time.
     *
     * A timestamp with no offset at all is read as UTC, which is what
     * Komga means by one.
     */
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
     * How Komga wants a time sent to it.
     *
     * Milliseconds are kept: two positions saved in the same second are
     * ordinary when a page turn is what triggers the save, and Komga
     * compares these instants to decide which of them is newer.
     */
    fun format(epochMillis: Long): String =
        FORMAT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
}
