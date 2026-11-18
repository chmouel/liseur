package com.chmouel.liseur.readaloud

import com.chmouel.liseur.readaloud.ReadAloudProblems.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudProblemsTest {
    private fun notice(bookId: String, notice: ReadAloudNotice) = ReadAloudProblems.next(null, Event.Notice(bookId, notice))

    private fun next(current: ReadAloudProblem?, event: Event) = ReadAloudProblems.next(current, event)

    @Test
    fun `a failure while reading is shown and can be retried`() {
        val shown = notice("book", ReadAloudNotice.Network)
        assertEquals(ReadAloudProblem("book", ReadAloudNotice.Network, recoverable = true), shown)
        for (retryable in listOf(ReadAloudNotice.RateLimited, ReadAloudNotice.Service, ReadAloudNotice.Output, ReadAloudNotice.TranslationFailed)) {
            assertEquals(ReadAloudProblem("book", retryable, recoverable = true), notice("book", retryable))
        }
    }

    @Test
    fun `a stop with nothing left to retry is shown as such`() {
        for (stopped in listOf(
            ReadAloudNotice.InvalidKey,
            ReadAloudNotice.InvalidVoice,
            ReadAloudNotice.TermsRequired,
            ReadAloudNotice.NoSpeech,
            ReadAloudNotice.NotSetUp,
            ReadAloudNotice.Unavailable,
        )) {
            assertEquals(ReadAloudProblem("book", stopped, recoverable = false), notice("book", stopped))
        }
    }

    @Test
    fun `an informational notice leaves the problem alone`() {
        assertNull(notice("book", ReadAloudNotice.SelectionNotFound))
        val current = notice("book", ReadAloudNotice.Network)
        assertEquals(current, next(current, Event.Notice("book", ReadAloudNotice.SelectionNotFound)))
    }

    @Test
    fun `a new start clears it, whatever the book`() {
        val current = notice("book", ReadAloudNotice.InvalidKey)
        assertNull(next(current, Event.Started("book")))
        assertNull(next(current, Event.Started("other")))
    }

    @Test
    fun `audio playing clears it only for its own book`() {
        val current = notice("book", ReadAloudNotice.Network)
        assertEquals(current, next(current, Event.Playing("other")))
        assertNull(next(current, Event.Playing("book")))
    }

    @Test
    fun `audio a failure overtook before it was read leaves the problem`() {
        val failed = notice("book", ReadAloudNotice.Output)
        assertEquals(failed, ReadAloudProblems.playingNow(failed, "book", failedWhenPlayed = 0, failedNow = 1))
        assertNull(ReadAloudProblems.playingNow(failed, "book", failedWhenPlayed = 1, failedNow = 1))
    }

    @Test
    fun `dismissing clears the book's problem`() {
        val current = notice("book", ReadAloudNotice.InvalidKey)
        assertEquals(current, next(current, Event.Dismissed("other")))
        assertNull(next(current, Event.Dismissed("book")))
        assertNull(next(current, Event.Dismissed()))
    }

    @Test
    fun `a settings change clears it`() {
        val current = notice("book", ReadAloudNotice.InvalidVoice)
        assertNull(next(current, Event.SettingsChanged))
    }

    @Test
    fun `the latest notice is the one shown`() {
        val service = notice("book", ReadAloudNotice.Service)
        assertEquals(
            ReadAloudProblem("book", ReadAloudNotice.RateLimited, recoverable = true),
            next(service, Event.Notice("book", ReadAloudNotice.RateLimited)),
        )
    }
}
