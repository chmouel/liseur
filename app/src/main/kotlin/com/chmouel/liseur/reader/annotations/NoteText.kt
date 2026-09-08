package com.chmouel.liseur.reader.annotations

import java.util.concurrent.TimeUnit

/** What a note says about itself, worked out without a screen. */
object NoteText {
    /**
     * Whether a note was reworked after it was written.
     *
     * A mark's two stamps are not in the same unit: `created_at` is in
     * milliseconds and `updated_at` in microseconds, since the latter goes
     * to liseur-sync verbatim. Saving a note stamps it a moment after
     * creating it, so anything inside a minute is the same sitting.
     */
    fun edited(createdAtMs: Long, updatedAtMicros: Long): Boolean {
        if (updatedAtMicros <= 0L) return false
        val updatedAtMs = TimeUnit.MICROSECONDS.toMillis(updatedAtMicros)
        return updatedAtMs - createdAtMs > TimeUnit.MINUTES.toMillis(1)
    }

    /**
     * The passage and the note as one thing to hand to another app: the
     * words first, quoted, and what the reader made of them underneath.
     */
    fun share(passage: String?, note: String?): String {
        val quoted = passage?.trim()?.takeIf { it.isNotEmpty() }?.let { "\u201C$it\u201D" }
        val body = note?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(quoted, body).joinToString("\n\n")
    }
}
