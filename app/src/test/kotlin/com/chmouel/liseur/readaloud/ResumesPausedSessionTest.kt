package com.chmouel.liseur.readaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumesPausedSessionTest {
    private fun session(bookId: String, playing: Boolean = false, preparing: Boolean = false) =
        ReadAloudUi(bookId = bookId, playing = playing, utterance = null, preparing = preparing)

    @Test
    fun `the book's own paused session plays on`() {
        assertTrue(resumesPausedSession(session("book"), "book"))
    }

    @Test
    fun `another book's paused session is refused`() {
        assertFalse(resumesPausedSession(session("other"), "book"))
    }

    @Test
    fun `a session already playing is refused`() {
        assertFalse(resumesPausedSession(session("book", playing = true), "book"))
    }

    @Test
    fun `a session still landing is refused`() {
        assertFalse(resumesPausedSession(session("book", preparing = true), "book"))
    }

    @Test
    fun `no session at all is refused`() {
        assertFalse(resumesPausedSession(null, "book"))
    }
}
