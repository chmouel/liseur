package com.chmouel.liseur.translate

import com.chmouel.liseur.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pause-reason vocabulary together: what execute keeps for an
 * exception, what resumable lets go on from, and what the sheet's pause
 * banner and error code read back. A typo on any side silently breaks
 * the resume path.
 */
class WholeBookPauseReasonsTest {
    /** Every exception shape execute's catch can see, with the reason it must keep. */
    private val reasons = listOf(
        TranslationError.Changed() to "settings_changed",
        TranslationError.SettingsChanged() to "settings_changed",
        TranslationError.Network(IOException("offline")) to "network",
        TranslationError.RateLimited(429) to "rate_limited:429",
        TranslationError.RateLimited(402) to "rate_limited:402",
        TranslationError.Service(503) to "service:503",
        TranslationError.Service(401) to "service:401",
        TranslationError.Refused() to "service",
        IOException("disk full") to "storage",
    )

    @Test
    fun `every pausing exception keeps the reason it should`() {
        reasons.forEach { (error, reason) ->
            assertEquals("$error", reason, wholeBookPauseReasonFor(error))
        }
    }

    @Test
    fun `every kept reason is classified for resuming`() {
        // A passing trouble goes on alone; anything the reader or the
        // settings must settle needs the check first.
        val automatic = listOf(
            "network" to true,
            "rate_limited:429" to true,
            "rate_limited:402" to false,
            "service:503" to true,
            "service:408" to true,
            "service:401" to false,
            "service" to false,
            "settings_changed" to false,
            "storage" to false,
        )
        automatic.forEach { (reason, expected) ->
            assertEquals(reason, expected, resumable(reason, automatic = true))
        }
        // Asked for by the reader, every pause but a changed setting or a
        // storage failure may go on without the check.
        reasons.forEach { (_, reason) ->
            val expected = reason != "settings_changed" && reason != "storage"
            assertEquals(reason, expected, resumable(reason, automatic = false))
        }
    }

    @Test
    fun `the sheet names every kept reason and reads back its HTTP code`() {
        val labels = listOf(
            "settings_changed" to R.string.whole_book_settings_changed,
            "network" to R.string.whole_book_reason_network,
            "rate_limited:429" to R.string.whole_book_reason_rate,
            "service:503" to R.string.whole_book_reason_service,
            "service" to R.string.whole_book_reason_service,
            "storage" to R.string.whole_book_reason_storage,
            // Written by the timeout and the killed-app paths, not by execute's mapping.
            "timeout" to R.string.whole_book_reason_timeout,
            "interrupted" to R.string.whole_book_reason_interrupted,
        )
        labels.forEach { (reason, label) ->
            assertEquals(reason, label, wholeBookPauseReason(reason))
        }
        // Only a service or rate-limit pause keeps the HTTP status it failed on.
        listOf(
            "rate_limited:429" to 429,
            "rate_limited:402" to 402,
            "service:503" to 503,
            "service:408" to 408,
            "service:401" to 401,
        ).forEach { (reason, code) ->
            assertEquals(reason, code, wholeBookPauseCode(reason))
        }
        listOf("settings_changed", "network", "service", "storage", "timeout", "interrupted", null)
            .forEach { reason -> assertEquals("$reason", null, wholeBookPauseCode(reason)) }
    }
}
