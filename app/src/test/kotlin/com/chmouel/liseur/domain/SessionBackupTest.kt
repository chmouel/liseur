package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SessionBackupTest {
    @Test
    fun `session decode rejects negative, overflowing or reversed times and out-of-range progressions`() {
        val badInputs = listOf(
            // Negative started_at
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":-1,"ended_at":10,"duration_ms":5}]}]}""",
            // Overflowing timestamp
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":99999999999999999999,"ended_at":10,"duration_ms":5}]}]}""",
            // Reversed ended_at < started_at
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":20,"ended_at":10,"duration_ms":5}]}]}""",
            // Negative duration_ms
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":10,"ended_at":20,"duration_ms":-5}]}]}""",
            // Negative idle_ms
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":10,"ended_at":20,"duration_ms":5,"idle_ms":-1}]}]}""",
            // Out-of-range start_progression
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":10,"ended_at":20,"duration_ms":5,"start_progression":-0.1}]}]}""",
            // Out-of-range end_progression
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":10,"ended_at":20,"duration_ms":5,"end_progression":1.01}]}]}""",
            // Duplicate book_id
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[]},{"book_id":"b1","sessions":[]}]}""",
            // A sitting longer than a day, which would let totals overflow once summed
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":0,"ended_at":10,"duration_ms":${MAX_SESSION_DURATION_MS + 1}}]}]}""",
            // Idle time beyond any plausible open-session span
            """{"format":1,"application":"liseur","books":[{"book_id":"b1","sessions":[{"started_at":0,"ended_at":10,"duration_ms":5,"idle_ms":${MAX_SESSION_IDLE_MS + 1}}]}]}""",
        )
        for (bad in badInputs) {
            var rejected = false
            try {
                decodeSessionBackup(bad.reader())
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Expected failure for: $bad", rejected)
        }
    }

    @Test
    fun `one archive cannot overflow the summed reading time`() {
        assertTrue(MAX_SESSIONS.toLong() * MAX_SESSION_DURATION_MS < Long.MAX_VALUE / 2)
    }

    @Test
    fun `null progressions and idle time survive a round trip`() {
        val json = """{
            "format": 1,
            "application": "liseur",
            "books": [
                {
                    "book_id": "book:1",
                    "title": "Dune",
                    "author": null,
                    "sessions": [
                        {
                            "started_at": 1000,
                            "ended_at": 2000,
                            "duration_ms": 900,
                            "idle_ms": 100,
                            "start_progression": null,
                            "end_progression": 0.25
                        },
                        {
                            "started_at": 3000,
                            "ended_at": 3000,
                            "duration_ms": 0,
                            "idle_ms": null,
                            "start_progression": 0.0,
                            "end_progression": null
                        }
                    ]
                }
            ]
        }"""
        val decoded = decodeSessionBackup(json.reader())
        assertEquals(1, decoded.size)
        val book = decoded.single()
        assertEquals("book:1", book.bookId)
        assertEquals("Dune", book.title)
        assertNull(book.author)
        assertEquals(
            listOf(
                BackedUpSession(1000L, 2000L, 900L, 100L, null, 0.25),
                BackedUpSession(3000L, 3000L, 0L, null, 0.0, null),
            ),
            book.sessions,
        )
    }
}
