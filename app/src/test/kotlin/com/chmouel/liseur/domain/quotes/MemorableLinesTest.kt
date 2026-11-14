package com.chmouel.liseur.domain.quotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorableLinesTest {
    private fun best(language: String?, vararg paragraphs: String): List<String> =
        MemorableLines.find(paragraphs.map { QuoteParagraph(it) }, language).map { it.text }

    @Test
    fun `a general line standing alone beats plot, dialogue and lines that lean on the one before`() {
        val found = best(
            "en",
            "Marcus walked to the station with Helen and Jacob after dinner on Tuesday.",
            "\u201CWe should go now,\u201D she said, looking back at the house behind them.",
            "He did not answer her, and they kept on walking down the narrow lane.",
            "We never know how much we love a place until we have to leave it behind.",
        )
        assertEquals("We never know how much we love a place until we have to leave it behind.", found.first())
    }

    @Test
    fun `lines that cannot stand on their own are refused outright`() {
        assertFalse(MemorableLines.acceptable("Too short to say much."))
        assertFalse(MemorableLines.acceptable("this one starts in the middle of a thought and goes on for a while."))
        assertFalse(MemorableLines.acceptable("This sentence never comes to an end and just trails away into nothing"))
        assertFalse(MemorableLines.acceptable("CHAPTER ONE IN WHICH EVERYTHING HAPPENS AT ONCE AND LOUDLY."))
        assertFalse(MemorableLines.acceptable("Copyright © 2021 by the author, all rights reserved worldwide."))
        assertFalse(MemorableLines.acceptable("She left (without a word and never came back to see us again."))
        assertTrue(MemorableLines.acceptable("Nothing is so painful to the human mind as a great and sudden change."))
    }

    @Test
    fun `chinese lines are measured in characters and need no capital`() {
        assertTrue(MemorableLines.acceptable("人生最大的幸福，莫过于在平凡的日子里找到属于自己的光。"))
        assertFalse(MemorableLines.acceptable("他走了。"))
    }

    @Test
    fun `french dialogue and anaphora lose to a general line`() {
        val found = best(
            "fr",
            "\u2014 Viens ici tout de suite, dit-elle à son frère qui jouait dehors.",
            "Il regarda longtemps la mer sans rien dire à personne ce soir-là.",
            "On ne voit bien qu\u2019avec le cœur, et l\u2019essentiel est invisible pour les yeux.",
        )
        assertEquals("On ne voit bien qu\u2019avec le cœur, et l\u2019essentiel est invisible pour les yeux.", found.first())
    }

    @Test
    fun `one line per paragraph and no repeats`() {
        val line = "Every family keeps its own silence, and every silence keeps its own family."
        val found = best("en", "$line $line", line, "A second sentence that is long enough to be acceptable here.")
        assertEquals(1, found.count { it == line })
    }
}
