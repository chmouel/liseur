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
    fun `missing and empty targets have no highlight`() {
        assertNull(highlightWord("sanguine", ""))
        assertNull(highlightWord("sanguine", "  "))
        assertNull(highlightWord("sanguine", "\u0301"))
        assertNull(highlightWord("sanguine", "hope"))
        assertNull(highlightWord("", "hope"))
    }
}
