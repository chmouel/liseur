package com.chmouel.liseur.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpensEditionPickerTest {
    @Test
    fun `only an unmatched book that is not kept off hardcover opens the picker`() {
        assertTrue(opensEditionPicker(matched = false, excluded = false))
        assertFalse(opensEditionPicker(matched = false, excluded = true))
        assertFalse(opensEditionPicker(matched = true, excluded = false))
    }
}
