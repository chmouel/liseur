package com.chmouel.liseur.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocabularyHighlightTest {
    @Test
    fun `highlight keeps offsets after case and accent folding`() {
        val sentence = "😀 À côté, un CAFE\u0301 !"
        val range = highlightWord(sentence, "café")!!
        assertEquals("CAFE\u0301", sentence.substring(range))
        assertEquals("côté", "À côté de moi".substring(highlightWord("À côté de moi", "COTE")!!))
        assertEquals("İSTANBUL", "İSTANBUL".substring(highlightWord("İSTANBUL", "istanbul")!!))
    }

    @Test
    fun `highlight supports non Latin words and phrases`() {
        assertEquals(2..3, highlightWord("喜欢读书。", "读书"))
        val sentence = "A SANGUINE temperament, sanguine still."
        assertEquals("SANGUINE temperament", sentence.substring(highlightWord(sentence, "sanguine temperament")!!))
        assertEquals(2..9, highlightWord(sentence, "sanguine"))
    }

    @Test
    fun `highlight handles case folds that expand or have final forms`() {
        assertEquals("Straße", "Die Straße".substring(highlightWord("Die Straße", "STRASSE")!!))
        assertEquals("ΚΟΣΜΟΣ", "ΚΟΣΜΟΣ".substring(highlightWord("ΚΟΣΜΟΣ", "κόσμος")!!))
    }

    @Test
    fun `highlight skips embedded words before a complete match`() {
        assertEquals(20..22, highlightWord("The department buys art.", "art"))
        assertNull(highlightWord("The department.", "art"))
        assertEquals(15..18, highlightWord("decafeteria, a CAFÉ.", "cafe"))
        assertNull(highlightWord("кумир", "мир"))
    }

    @Test
    fun `highlight requires the complete phrase at both ends`() {
        val sentence = "start format; art form."
        assertEquals("art form", sentence.substring(highlightWord(sentence, "art form")!!))
        assertNull(highlightWord("start format", "art form"))
        assertNull(highlightWord("can't", "can"))
    }

    @Test
    fun `highlight preserves matches in scripts written without spaces`() {
        assertEquals(2..3, highlightWord("喜欢读书。", "读书"))
        assertEquals(2..3, highlightWord("カタカナ", "カナ"))
        assertEquals(3..6, highlightWord("ฉันอ่าน", "อ่าน"))
    }

    @Test
    fun `highlight cannot select only part of an expanded character`() {
        assertNull(highlightWord("ß", "s"))
        assertEquals(0..0, highlightWord("ß", "ss"))
    }

    @Test
    fun `highlight preserves marks that distinguish words outside Latin and Greek`() {
        assertEquals(5..6, highlightWord("काला कल", "कल"))
        assertNull(highlightWord("काला", "कल"))
        assertEquals(4..6, highlightWord("мой мои", "мои"))
    }

    @Test
    fun `missing and empty targets have no highlight`() {
        assertNull(highlightWord("sanguine", ""))
        assertNull(highlightWord("sanguine", "  "))
        assertNull(highlightWord("sanguine", "\u0301"))
        assertNull(highlightWord("sanguine", "hope"))
        assertNull(highlightWord("", "hope"))
    }
}
