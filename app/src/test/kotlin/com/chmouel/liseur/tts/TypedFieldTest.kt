package com.chmouel.liseur.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedFieldTest {
    private val a = "https://a.example/v1"
    private val b = "https://b.example/v1"

    @Test
    fun anUntouchedFieldShowsTheNewlySavedValue() {
        assertTrue(TypedField.follows(a, a, b))
        assertTrue(TypedField.follows("", "", a))
    }

    @Test
    fun anEarlierSaveLandingLateLeavesTheNewerAddressShown() {
        // A then B saved; A lands first while the field shows B.
        assertFalse(TypedField.follows(b, "", a, newer = true))
        assertTrue(TypedField.follows(b, "", b, newer = true))
    }

    @Test
    fun goingBackToTheFirstAddressIsNotUndoneByTheOneBetween() {
        // A saved; B then A again submitted; B lands while the field shows A.
        assertFalse(TypedField.follows(a, a, b, newer = true))
        assertTrue(TypedField.follows(a, a, a, newer = true))
    }

    @Test
    fun aModelBeingTypedIsNotReplacedByOneChosenForTheReader() {
        assertFalse(TypedField.follows("orpheus-v1-eng", "kokoro", "canopylabs/orpheus-arabic-saudi"))
    }
}
