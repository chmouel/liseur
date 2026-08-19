package com.chmouel.liseur.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory that keeps the reader and the shelf from asking twice.
 *
 * Nothing here reaches disk on purpose: an answer lasts the run, so a
 * refusal is not permanent and a book still only on this device is
 * worth raising again the next time the app starts.
 */
class UploadPromptsTest {

    @Test
    fun `a book nobody has answered for is unanswered`() {
        assertFalse(UploadPrompts().wasAnswered("file:///sd/a.epub"))
    }

    @Test
    fun `an answer is remembered`() {
        val prompts = UploadPrompts()
        prompts.answer("file:///sd/a.epub")
        assertTrue(prompts.wasAnswered("file:///sd/a.epub"))
    }

    /** Answering for one book says nothing about any other. */
    @Test
    fun `an answer covers only the book it was about`() {
        val prompts = UploadPrompts()
        prompts.answer("file:///sd/a.epub")
        assertFalse(prompts.wasAnswered("file:///sd/b.epub"))
    }

    @Test
    fun `answering twice is answering once`() {
        val prompts = UploadPrompts()
        prompts.answer("file:///sd/a.epub")
        prompts.answer("file:///sd/a.epub")
        assertTrue(prompts.wasAnswered("file:///sd/a.epub"))
        assertTrue(prompts.answered.value.size == 1)
    }

    /** What the shelf filters its offer with has to see every answer. */
    @Test
    fun `answers are visible to whoever is watching`() {
        val prompts = UploadPrompts()
        prompts.answer("file:///sd/a.epub")
        prompts.answer("file:///sd/b.epub")
        assertTrue(
            prompts.answered.value == setOf("file:///sd/a.epub", "file:///sd/b.epub"),
        )
    }
}
