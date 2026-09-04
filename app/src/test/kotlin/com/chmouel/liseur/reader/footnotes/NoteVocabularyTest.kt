package com.chmouel.liseur.reader.footnotes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteVocabularyTest {

    @Test
    fun `the epub vocabulary names a note`() {
        assertTrue(NoteVocabulary.isNote("footnote", "", "div"))
        assertTrue(NoteVocabulary.isNote("endnote", "", "li"))
        assertTrue(NoteVocabulary.isNote("rearnote", "", "div"))
        assertTrue(NoteVocabulary.isNote("note", "", "section"))
    }

    @Test
    fun `a prefixed epub type is read past its prefix`() {
        assertTrue(NoteVocabulary.isNote("epub:footnote", "", "div"))
    }

    @Test
    fun `one word among several is enough`() {
        assertTrue(NoteVocabulary.isNote("backlink footnote", "", "div"))
    }

    @Test
    fun `the aria vocabulary names a note too`() {
        assertTrue(NoteVocabulary.isNote("", "doc-footnote", "div"))
        assertTrue(NoteVocabulary.isNote("", "doc-endnote", "div"))
    }

    @Test
    fun `an unlabelled aside is taken at its word`() {
        assertTrue(NoteVocabulary.isNote("", "", "aside"))
        assertTrue(NoteVocabulary.isNote("", "", "ASIDE"))
    }

    @Test
    fun `a chapter is not a note however it is spelled`() {
        assertFalse(NoteVocabulary.isNote("chapter", "", "section"))
        assertFalse(NoteVocabulary.isNote("", "doc-chapter", "div"))
        assertFalse(NoteVocabulary.isNote("", "", "div"))
    }

    @Test
    fun `a word that merely contains a note word is not one`() {
        assertFalse(NoteVocabulary.isNote("footnotes", "", "div"))
        assertFalse(NoteVocabulary.isNote("noteref", "", "div"))
    }

    @Test
    fun `a reference is recognised by either vocabulary`() {
        assertTrue(NoteVocabulary.isNoteRef("noteref", ""))
        assertTrue(NoteVocabulary.isNoteRef("epub:noteref", ""))
        assertTrue(NoteVocabulary.isNoteRef("", "doc-noteref"))
        assertFalse(NoteVocabulary.isNoteRef("footnote", ""))
        assertFalse(NoteVocabulary.isNoteRef("", ""))
    }

    @Test
    fun `a token list may be written across any whitespace`() {
        // The injected script splits on `/\s+/`, and a book is free to wrap
        // a long `epub:type` across a line. Reading only spaces here made
        // the two disagree about the same element.
        assertTrue(NoteVocabulary.isNote("backmatter\tfootnote", "", "div"))
        assertTrue(NoteVocabulary.isNote("backmatter\nfootnote", "", "div"))
        assertTrue(NoteVocabulary.isNote("", "doc-chapter\tdoc-footnote", "div"))
        assertTrue(NoteVocabulary.isNoteRef("backmatter\nnoteref", ""))
    }
}
