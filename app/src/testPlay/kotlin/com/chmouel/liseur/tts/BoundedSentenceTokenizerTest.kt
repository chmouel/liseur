package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.tokenizer.TextTokenizer

@OptIn(ExperimentalReadiumApi::class)
class BoundedSentenceTokenizerTest {

    // Stands in for ICU: a sentence ends after ". ".
    private val sentences = TextTokenizer { data ->
        val ranges = mutableListOf<IntRange>()
        var start = 0
        Regex("""\. """).findAll(data).forEach {
            ranges += start..it.range.first
            start = it.range.last + 1
        }
        if (start < data.length) ranges += start until data.length
        ranges
    }

    private fun tokenize(data: String, min: Int = 40, max: Int = 600) =
        BoundedSentenceTokenizer(sentences, min, max).tokenize(data).map { data.substring(it) }

    @Test
    fun mergesShortSentencesWithTheNextOne() {
        val text = "No. Yes. This sentence is long enough to stand on its own here. And so is this one, which follows it closely."
        assertEquals(
            listOf(
                "No. Yes. This sentence is long enough to stand on its own here.",
                "And so is this one, which follows it closely.",
            ),
            tokenize(text),
        )
    }

    @Test
    fun aShortTailJoinsTheSentenceBeforeIt() {
        val text = "This sentence is long enough to stand on its own here. The end."
        assertEquals(listOf(text), tokenize(text))
    }

    @Test
    fun aLoneShortTextStaysWhole() {
        assertEquals(listOf("IX."), tokenize("IX."))
    }

    @Test
    fun splitsLongSentencesAtClausesThenSpacesThenAnywhere() {
        val clauses = "a".repeat(30) + ", " + "b".repeat(30) + "; " + "c".repeat(30)
        assertEquals(
            listOf("a".repeat(30) + ",", "b".repeat(30) + ";", "c".repeat(30)),
            tokenize(clauses, min = 10, max = 40),
        )
        val words = List(12) { "word" }.joinToString(" ")
        assertTrue(tokenize(words, min = 5, max = 20).all { it.length <= 20 && !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals(listOf("x".repeat(20), "x".repeat(20), "x".repeat(5)), tokenize("x".repeat(45), min = 5, max = 20))
    }

    @Test
    fun keepsEveryLetterInOrder() {
        val text = "Short. " + List(80) { "and the time traveller spoke again, slowly" }.joinToString(" ") + ". Fine. Done."
        val pieces = BoundedSentenceTokenizer(sentences).tokenize(text)
        pieces.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first) }
        assertTrue(pieces.all { it.first >= 0 && it.last < text.length && it.count() <= 600 })
        assertEquals(text.filterNot(Char::isWhitespace), pieces.joinToString("") { text.substring(it) }.filterNot(Char::isWhitespace))
    }
}
