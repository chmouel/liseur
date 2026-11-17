package com.chmouel.liseur.domain

import org.junit.Assert.*
import org.junit.Test

class PersonalEvaluationTest {
    @Test
    fun `rating values use exact half-star units and clearing is allowed`() {
        for (rating in 2..10) assertTrue(PersonalEvaluation.valid("rating", rating.toString()))
        for (rating in listOf("0", "1", "11", "9.0", "09", "5 stars")) assertFalse(PersonalEvaluation.valid("rating", rating))
        assertTrue(PersonalEvaluation.valid("rating", null))
    }

    @Test
    fun `review limit measures UTF-8 bytes and refuses empty or null-containing text`() {
        assertTrue(PersonalEvaluation.valid("review", "é".repeat(4096)))
        assertFalse(PersonalEvaluation.valid("review", "é".repeat(4097)))
        assertFalse(PersonalEvaluation.valid("review", " \n\t"))
        assertFalse(PersonalEvaluation.valid("review", "a\u0000b"))
        assertTrue(PersonalEvaluation.valid("review", null))
        assertFalse(PersonalEvaluation.valid("unknown", null))
    }
}
