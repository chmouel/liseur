package com.chmouel.liseur.reader.annotations

import android.app.Activity
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TextIntentsTest {
    @Test
    fun `external app fallback sends only the first word of a passage`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (text in listOf("harnesses in the book", "harnesses\nin the book", "harnesses\tin the book")) {
            activity.lookUpExternally(text, "https://en.wiktionary.org")
            assertEquals("https://en.wiktionary.org/wiki/harnesses",
                shadowOf(activity).nextStartedActivity.dataString)
        }
    }

    @Test
    fun `explicit full entry links preserve multiword dictionary titles`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.openDictionaryEntry("give up", "https://en.wiktionary.org")
        assertEquals("https://en.wiktionary.org/wiki/give%20up",
            shadowOf(activity).nextStartedActivity.dataString)
    }
}
