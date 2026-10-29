package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class DescriptionTextTest {
    @Test
    fun `markup becomes plain text without images or links`() {
        val text = descriptionText(
            "<p>First <b>part</b> &amp; <a href=\"https://x\">link</a>.</p><img src=\"x.png\"/><p>Second.</p>",
        )
        assertEquals("First part & link.\n\nSecond.", text)
    }

    @Test
    fun `plain text keeps its line breaks`() {
        assertEquals("One\n\nTwo", descriptionText("One\n\n\n\nTwo", html = false))
    }

    @Test
    fun `blank and image only descriptions are absent`() {
        assertNull(descriptionText("<p> <img src=\"x\"/> </p>"))
        assertNull(descriptionText(null))
    }
}
