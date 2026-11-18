package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudAwayTest {

    @Test
    fun `nothing is away from without a voice`() {
        assertFalse(readAloudAway(voice = false, playing = true, followDetached = true, pausedShowing = null))
        assertFalse(readAloudAway(voice = false, playing = false, followDetached = true, pausedShowing = false))
    }

    @Test
    fun `playing, only the reader's own move away counts`() {
        assertFalse(readAloudAway(voice = true, playing = true, followDetached = false, pausedShowing = null))
        assertTrue(readAloudAway(voice = true, playing = true, followDetached = true, pausedShowing = null))
    }

    @Test
    fun `paused, the page in front decides`() {
        assertFalse(readAloudAway(voice = true, playing = false, followDetached = false, pausedShowing = true))
        assertTrue(readAloudAway(voice = true, playing = false, followDetached = false, pausedShowing = false))
    }

    @Test
    fun `paused on the sentence's own page is not away, whichever move detached the follow`() {
        assertFalse(readAloudAway(voice = true, playing = false, followDetached = true, pausedShowing = true))
    }

    @Test
    fun `paused with the page still unasked is not away`() {
        assertFalse(readAloudAway(voice = true, playing = false, followDetached = false, pausedShowing = null))
        assertFalse(readAloudAway(voice = true, playing = false, followDetached = true, pausedShowing = null))
    }
}
