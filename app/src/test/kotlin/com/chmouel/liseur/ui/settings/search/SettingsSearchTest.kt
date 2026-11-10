package com.chmouel.liseur.ui.settings.search

import com.chmouel.liseur.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private fun row(title: String, summary: String? = null) =
        SettingsResult(SettingsEntry(title, Route.Settings, 0), title, summary, "Settings")

    private fun titles(query: String, rows: List<SettingsResult>) = SettingsSearch.match(query, rows).map { it.title }

    @Test
    fun accentsAndCaseDoNotMatter() {
        val rows = listOf(row("Thème"), row("Luminosité"))
        assertEquals(listOf("Thème"), titles("THEME", rows))
        assertEquals(listOf("Luminosité"), titles("luminosite", rows))
    }

    @Test
    fun everyWordMustAppearSomewhere() {
        val rows = listOf(
            row("Volume keys", "Turn pages with the volume buttons"),
            row("Keep screen on"),
        )
        assertEquals(listOf("Volume keys"), titles("volume pages", rows))
        assertEquals(emptyList<String>(), titles("volume screen", rows))
    }

    @Test
    fun textWithoutSpacesMatchesInsideWords() {
        val rows = listOf(row("翻页方式"), row("保持屏幕常亮"))
        assertEquals(listOf("保持屏幕常亮"), titles("屏幕", rows))
    }

    @Test
    fun titleMatchesComeBeforeOtherMatches() {
        val rows = listOf(
            row("Page turn style", "How the page moves"),
            row("Pinch to resize", "Change the text size"),
            row("Book context"),
            row("Fine text spacing"),
            row("Text alignment"),
            row("Font size", "Text size"),
        )
        assertEquals(
            listOf("Text alignment", "Fine text spacing", "Book context", "Pinch to resize", "Font size"),
            titles("text", rows),
        )
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertTrue(SettingsSearch.match("  ", listOf(row("Theme"))).isEmpty())
    }

    @Test
    fun rowsThePageWouldHideAreLeftOut() {
        val strings = { id: Int -> "s$id" }
        val keys = { context: SearchContext ->
            SettingsSearch.resolve(SettingsEntries.all(), context, strings).map { it.entry.key }
        }
        assertTrue(SettingsEntries.Columns.key !in keys(SearchContext(columnsAvailable = false)))
        assertTrue(SettingsEntries.Columns.key in keys(SearchContext(columnsAvailable = true)))
        assertTrue(SettingsEntries.ColorEInk.key !in keys(SearchContext(eInk = false)))
        assertTrue(SettingsEntries.ColorEInk.key in keys(SearchContext(eInk = true)))
        assertTrue(SettingsEntries.DictionarySite.key !in keys(SearchContext(dictionaryLookup = false)))
    }

    @Test
    fun aNestedPageShowsItsParentInThePath() {
        val names = mapOf(
            SettingsEntries.TapZones.title to "Tap zones",
            Route.ReadingNavigation.settingsTitle to "Reading and navigation",
            Route.PageTaps.settingsTitle to "Page taps",
        )
        val result = SettingsSearch.resolve(listOf(SettingsEntries.TapZones), SearchContext()) { names[it] ?: "?" }
        assertEquals("Reading and navigation › Page taps", result.single().path)
    }

    @Test
    fun everyRowHasItsOwnKey() {
        val keys = SettingsEntries.all().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
