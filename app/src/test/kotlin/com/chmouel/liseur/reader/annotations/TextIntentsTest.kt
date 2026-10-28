package com.chmouel.liseur.reader.annotations

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.selectionActionText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TextIntentsTest {
    @Test
    fun `translated popup and dictionary selections open Wikipedia using displayed text and language`() = runTest {
        val entry = SelectionActionEntry(SelectionAction.WIKIPEDIA, SelectionActionStyle.BOTH)
        for (inSheet in listOf(false, true)) {
            val text = selectionActionText("original words", true, inSheet, "mots affichés") { "mots affichés" }
            val language = selectionSearchLanguage(listOf("en"), true, "fr")
            val intent = selectionWebsiteIntent(entry, requireNotNull(text), language)!!
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://fr.wikipedia.org/w/index.php?search=mots%20affich%C3%A9s", intent.dataString)
            assertNull(intent.extras)
        }
    }

    @Test
    fun `website intents launch their HTTPS handler and report a missing handler`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val entry = SelectionActionEntry(SelectionAction.OPEN_LIBRARY, SelectionActionStyle.BOTH)
        val intent = selectionWebsiteIntent(entry, "A & B", null)!!
        activity.launchSelectionIntent(intent)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.selection_no_handler), ShadowToast.getTextOfLatestToast())

        shadowOf(activity.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "test.browser"
                name = "test.browser.Main"
                exported = true
            }
        })
        activity.launchSelectionIntent(intent)
        assertEquals("https://openlibrary.org/search?q=A%20%26%20B", shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test
    fun `external app fallback sends only the first word of a passage`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (text in listOf("harnesses in the book on the shelf", "harnesses\nin the book", "harnesses\r\nin")) {
            activity.lookUpExternally(text, "https://en.wiktionary.org")
            assertEquals("https://en.wiktionary.org/wiki/harnesses",
                shadowOf(activity).nextStartedActivity.dataString)
        }
    }

    @Test
    fun `external app fallback keeps a short phrase whole`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.lookUpExternally(" je ne\tsais  quoi ", "https://fr.wiktionary.org")
        assertEquals("https://fr.wiktionary.org/wiki/je%20ne%20sais%20quoi",
            shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test
    fun `explicit full entry links preserve multiword dictionary titles`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.openDictionaryEntry("give up", "https://en.wiktionary.org")
        assertEquals("https://en.wiktionary.org/wiki/give%20up",
            shadowOf(activity).nextStartedActivity.dataString)
    }
}
