package com.chmouel.liseur.ui.library.styles

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeQuoteTextTest {

    @Test
    fun `strips outer book quotation marks around passage`() {
        assertEquals(
            "Nous n’étions que trois, nous n’étions pas équipés pour nous en occuper.",
            "“Nous n’étions que trois, nous n’étions pas équipés pour nous en occuper.”".trimQuoteMarks(),
        )
        assertEquals(
            "Nous n’étions que trois.",
            "«\u00A0Nous n’étions que trois.\u00A0»".trimQuoteMarks(),
        )
        assertEquals(
            "Plain sentence without quotes.",
            "Plain sentence without quotes.".trimQuoteMarks(),
        )
    }
}
