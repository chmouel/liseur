package com.chmouel.liseur.ui.launch

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.res.Configuration
import com.chmouel.liseur.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ShortcutResourcesTest {
    @Test
    fun `library labels are present in every bundled locale`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((tag, label) in mapOf(
            "en" to "Library", "fr" to "Bibliothèque", "es" to "Biblioteca",
            "ru" to "Библиотека", "it" to "Biblioteca", "de" to "Bibliothek", "zh-Hans" to "书库",
        )) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(configuration)
            assertEquals(label, localized.getString(R.string.shortcut_library))
            assertTrue(localized.getString(R.string.shortcut_continue).isNotBlank())
            assertTrue(localized.getString(R.string.shortcut_stats).isNotBlank())
            assertTrue(localized.getString(R.string.shortcut_open_failed).isNotBlank())
        }
    }

    @Test
    fun `static resources declare only the three ordered fixed actions in this package`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val android = "http://schemas.android.com/apk/res/android"
        context.resources.getXml(R.xml.shortcuts).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "shortcut" -> {
                        ids += xml.getAttributeValue(android, "shortcutId")
                        val label = xml.getAttributeResourceValue(android, "shortcutShortLabel", 0)
                        assertTrue(context.getString(label).isNotBlank())
                        assertTrue(xml.getAttributeResourceValue(android, "icon", 0) != 0)
                    }
                    "intent" -> {
                        actions += xml.getAttributeValue(android, "action")
                        assertEquals(context.packageName, xml.getAttributeValue(android, "targetPackage"))
                        assertEquals(
                            "com.chmouel.liseur.MainActivity", xml.getAttributeValue(android, "targetClass"),
                        )
                    }
                    else -> assertEquals("shortcuts", xml.name)
                }
            }
        }
        assertEquals(listOf("continue_reading", "library", "reading_statistics"), ids)
        assertEquals(LaunchTarget.entries.map { it.action }, actions)
    }
}
