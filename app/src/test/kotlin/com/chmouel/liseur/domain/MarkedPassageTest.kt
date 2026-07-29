package com.chmouel.liseur.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkedPassageTest {

    private fun passage(
        href: String = "epub/text/chapter-4.xhtml",
        progression: Double? = 0.30,
        text: String? = "the great whale",
    ) = MarkedPassage(href, progression, text)

    @Test
    fun `the same words in the same place are the same mark`() {
        assertTrue(isSamePassage(passage(), passage()))
    }

    @Test
    fun `a word picked out of a longer highlight is that highlight`() {
        val mark = passage(text = "the great whale of the sea")
        val tapped = passage(progression = 0.301, text = "whale")
        assertTrue(isSamePassage(tapped, mark))
    }

    @Test
    fun `another passage on the same page is a mark of its own`() {
        // The bug this rule exists for: positions are only good to about
        // a page, so going by them alone made this edit the first mark.
        val mark = passage(text = "the great whale")
        val elsewhere = passage(progression = 0.302, text = "a patchwork quilt")
        assertFalse(isSamePassage(elsewhere, mark))
    }

    @Test
    fun `the same words further off in the chapter are not the same mark`() {
        val mark = passage(progression = 0.10, text = "whale")
        val later = passage(progression = 0.80, text = "whale")
        assertFalse(isSamePassage(later, mark))
    }

    @Test
    fun `a mark in another chapter is never the same one`() {
        val mark = passage(href = "epub/text/chapter-4.xhtml")
        val other = passage(href = "epub/text/chapter-5.xhtml")
        assertFalse(isSamePassage(other, mark))
    }

    @Test
    fun `without words to compare, being all but on top of it is enough`() {
        val mark = passage(text = null)
        assertTrue(isSamePassage(passage(progression = 0.301, text = null), mark))
        assertFalse(isSamePassage(passage(progression = 0.9, text = null), mark))
    }

    @Test
    fun `without a position there is nothing to go on`() {
        val mark = passage(progression = null, text = null)
        assertFalse(isSamePassage(passage(progression = null, text = null), mark))
    }

    @Test
    fun `case and surrounding space do not make a new mark`() {
        val mark = passage(text = "The Great Whale")
        assertTrue(isSamePassage(passage(text = "  the great whale "), mark))
    }
}
