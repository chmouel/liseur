package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.reader.annotations.HighlightTint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The registry of settings that travel between devices.
 *
 * Against real repositories on temporary files, because every bug this
 * file exists to catch lives in the join between the string on the wire
 * and the typed value in the store — which a fake map would simply
 * agree with. Twenty-one entries, each a small hand-written pair of
 * lambdas, is exactly the shape where one entry reads one setting and
 * writes another and nothing complains.
 *
 * The round-trip property is the important test here. Sync compares what
 * it read against what it later reads back, so a setting that does not
 * survive its own round trip does not merely fail to sync: it looks
 * changed on every pass, and two devices will push it at each other for
 * as long as both are running.
 */
class SyncableSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Unique per store, so one test can hold two devices' worth. */
    private var stores = 0

    private fun app() = AppSettingsRepository(
        PreferenceDataStoreFactory.create { folder.newFile("app${stores++}.preferences_pb") },
    )

    private fun reader() = ReaderPreferencesRepository(
        PreferenceDataStoreFactory.create { folder.newFile("reader${stores++}.preferences_pb") },
    )

    private fun registry() = syncableSettings(app(), reader())

    // -- The property that makes sync terminate ----------------------------

    @Test
    fun `every setting survives its own round trip untouched`() = runTest {
        for (setting in registry()) {
            val before = setting.read()
            assertTrue(
                "${setting.key} refused the value it just produced: $before",
                setting.write(before),
            )
            assertEquals("${setting.key} did not survive a round trip", before, setting.read())
        }
    }

    @Test
    fun `every setting survives a round trip after being changed`() = runTest {
        val a = app()
        val r = reader()
        configure(a, r)
        for (setting in syncableSettings(a, r)) {
            val before = setting.read()
            assertTrue("${setting.key} refused its own value: $before", setting.write(before))
            assertEquals("${setting.key} did not survive a round trip", before, setting.read())
        }
    }

    @Test
    fun `a value read from one device is understood by another`() = runTest {
        val source = app() to reader()
        configure(source.first, source.second)
        val sent = syncableSettings(source.first, source.second).associate { it.key to it.read() }

        val target = app() to reader()
        for (setting in syncableSettings(target.first, target.second)) {
            assertTrue(
                "${setting.key} refused a value another device produced",
                setting.write(sent.getValue(setting.key)),
            )
            assertEquals(sent.getValue(setting.key), setting.read())
        }
    }

    @Test
    fun `no key is registered twice`() {
        val keys = registry().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the registry is the settings meant to travel`() {
        assertEquals(
            setOf(
                "reader.font",
                "reader.font_size",
                "reader.theme",
                "reader.line_height",
                "reader.page_margins",
                "reader.page_turn_style",
                "reader.footer_mode",
                "reader.text_align",
                "reader.font_weight",
                "reader.hyphens",
                "reader.letter_spacing",
                "reader.word_spacing",
                "reader.paragraph_spacing",
                "reader.auto_scroll_speed",
                "app.scroll_mode",
                "app.resume_last_book",
                "app.dictionary_enabled",
                "app.dictionary_base_url",
                "app.definition_target",
                "app.highlight_tints",
                "app.highlight_tint_default",
            ),
            registry().map { it.key }.toSet(),
        )
    }

    @Test
    fun `nothing about a device is registered as travelling`() {
        // These are about this piece of hardware, not about how the
        // reader likes to read: a phone's volume keys and an e-ink
        // panel's refresh have no business arriving from a tablet.
        val keys = registry().map { it.key }.toSet()
        for (local in listOf(
            "app.volume_keys",
            "app.keep_screen_on",
            "reader.column_mode",
            "app.eink_mode",
            "app.color_eink",
            "app.dynamic_color",
        )) {
            assertFalse("$local should not travel", local in keys)
        }
    }

    // -- Absence is a value ------------------------------------------------

    @Test
    fun `a setting left to the publisher travels as unset and comes back unset`() = runTest {
        val r = reader()
        val nullable = listOf(
            "reader.line_height",
            "reader.page_margins",
            "reader.hyphens",
            "reader.letter_spacing",
            "reader.word_spacing",
            "reader.paragraph_spacing",
        )
        val registry = syncableSettings(app(), r).associateBy { it.key }

        // Set, then put back to "whatever the publisher asked for".
        r.setLineHeight(1.4)
        r.setPageMargins(1.2)
        r.setHyphens(true)
        r.setLetterSpacing(0.05)
        r.setWordSpacing(0.1)
        r.setParagraphSpacing(0.4)
        for (key in nullable) {
            assertTrue(registry.getValue(key).write(SETTING_UNSET))
            assertEquals(
                "$key should read as unset",
                SETTING_UNSET,
                registry.getValue(key).read(),
            )
        }

        val prefs = r.prefs.first()
        assertNull(prefs.lineHeight)
        assertNull(prefs.pageMargins)
        assertNull(prefs.hyphens)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.wordSpacing)
        assertNull(prefs.paragraphSpacing)
    }

    @Test
    fun `a setting that has no unset state refuses one`() = runTest {
        val r = reader()
        val registry = syncableSettings(app(), r).associateBy { it.key }
        r.setFontSize(1.5)

        assertFalse(registry.getValue("reader.font_size").write(SETTING_UNSET))
        assertEquals("1.5", registry.getValue("reader.font_size").read())
    }

    // -- Values that must be refused ---------------------------------------

    @Test
    fun `a number out of range is refused rather than clearing the setting`() = runTest {
        val r = reader()
        val registry = syncableSettings(app(), r).associateBy { it.key }
        r.setLineHeight(1.4)

        // The trap: the setter says "out of range" and "not set" the same
        // way, so passing this through would silently unset it.
        assertFalse(registry.getValue("reader.line_height").write("9.9"))
        assertEquals(1.4, r.prefs.first().lineHeight!!, 0.0001)
    }

    @Test
    fun `a value that is not a number at all is refused`() = runTest {
        val r = reader()
        val registry = syncableSettings(app(), r).associateBy { it.key }
        r.setLineHeight(1.4)

        assertFalse(registry.getValue("reader.line_height").write("abc"))
        assertFalse(registry.getValue("reader.line_height").write("NaN"))
        assertFalse(registry.getValue("reader.line_height").write(""))
        assertEquals(1.4, r.prefs.first().lineHeight!!, 0.0001)
    }

    @Test
    fun `an auto-scroll pace between two notches is refused`() = runTest {
        val r = reader()
        val registry = syncableSettings(app(), r).associateBy { it.key }
        r.setAutoScrollSpeed(7f)

        // The setter snaps to a whole notch, so taking 4.5 would file
        // 4.5 as agreed while storing 5, and this device would push its
        // own rounding back over the pace that was actually chosen.
        assertFalse(registry.getValue("reader.auto_scroll_speed").write("4.5"))
        assertEquals(7f, r.prefs.first().autoScrollSpeed, 0.0001f)
        assertTrue(registry.getValue("reader.auto_scroll_speed").write("5.0"))
        assertEquals(5f, r.prefs.first().autoScrollSpeed, 0.0001f)
    }

    @Test
    fun `a font from a newer build is refused instead of falling back`() = runTest {
        val r = reader()
        val registry = syncableSettings(app(), r).associateBy { it.key }
        val before = registry.getValue("reader.font").read()

        // The whole hazard in one line: `fromId` answers with the default
        // for an id it does not know, so accepting this would store the
        // default and push it back over a choice made on a newer build.
        assertFalse(registry.getValue("reader.font").write("a-font-from-2027"))
        assertEquals(before, registry.getValue("reader.font").read())
    }

    @Test
    fun `an unknown id is refused for every setting named by one`() = runTest {
        for (setting in registry()) {
            val before = setting.read()
            if (setting.write("\u0000not-an-id-anywhere")) {
                // Free-text settings legitimately accept anything; only
                // the id-named ones are under test here.
                continue
            }
            assertEquals("${setting.key} changed despite refusing", before, setting.read())
        }
    }

    @Test
    fun `a boolean only accepts a boolean`() = runTest {
        val a = app()
        val registry = syncableSettings(a, reader()).associateBy { it.key }
        assertFalse(registry.getValue("app.scroll_mode").write("1"))
        assertFalse(registry.getValue("app.scroll_mode").write("True"))
        assertFalse(registry.getValue("app.scroll_mode").write("yes"))
        assertTrue(registry.getValue("app.scroll_mode").write("true"))
        assertTrue(a.current().scrollMode)
    }

    @Test
    fun `a dictionary address that is not https is refused`() = runTest {
        val a = app()
        val registry = syncableSettings(a, reader()).associateBy { it.key }
        val before = registry.getValue("app.dictionary_base_url").read()

        assertFalse(registry.getValue("app.dictionary_base_url").write("http://evil.example.com"))
        assertFalse(registry.getValue("app.dictionary_base_url").write("not a url"))
        assertEquals(before, registry.getValue("app.dictionary_base_url").read())
    }

    // -- The palette, where absent and empty are different answers ---------

    @Test
    fun `a palette never chosen is not the same as one chosen empty`() = runTest {
        val a = app()
        val setting = syncableSettings(a, reader()).first { it.key == "app.highlight_tints" }

        // Never chosen. This must not travel as the default three, or it
        // would beat a device that deliberately chose none.
        assertEquals(SETTING_UNSET, setting.read())

        // Chosen empty: a real answer, and a different one.
        a.setOfferedHighlightTints(emptySet())
        assertEquals("", setting.read())

        assertTrue(setting.write(SETTING_UNSET))
        assertNull(a.offeredHighlightTintNames())
        assertEquals(SETTING_UNSET, setting.read())

        assertTrue(setting.write(""))
        assertEquals(emptySet<String>(), a.offeredHighlightTintNames())
        assertEquals("", setting.read())
    }

    @Test
    fun `a palette survives the wire in a stable order`() = runTest {
        val a = app()
        val setting = syncableSettings(a, reader()).first { it.key == "app.highlight_tints" }
        val chosen = setOf(HighlightTint.BLUE.name, HighlightTint.YELLOW.name)
        a.setOfferedHighlightTints(chosen)

        val sent = setting.read()
        assertTrue(setting.write(sent))
        assertEquals(chosen, a.offeredHighlightTintNames())
        assertEquals(sent, setting.read())
    }

    @Test
    fun `a palette naming a colour this build lacks is refused whole`() = runTest {
        val a = app()
        val setting = syncableSettings(a, reader()).first { it.key == "app.highlight_tints" }
        a.setOfferedHighlightTints(setOf(HighlightTint.YELLOW.name))

        // Half-accepting would silently drop the colour and push the
        // truncated palette back at the device that has it.
        assertFalse(setting.write("YELLOW,ULTRAVIOLET"))
        assertEquals(setOf(HighlightTint.YELLOW.name), a.offeredHighlightTintNames())
    }

    @Test
    fun `every stored tint stays legal whether or not the palette offers it`() = runTest {
        val a = app()
        val setting = syncableSettings(a, reader()).first { it.key == "app.highlight_tint_default" }
        for (tint in HighlightTint.entries) {
            assertTrue("${tint.name} should be a legal default", setting.write(tint.name))
            assertEquals(tint.name, setting.read())
        }
        assertFalse(setting.write("CHARTREUSE"))
    }

    @Test
    fun `every setting that relays out the page says so`() = runTest {
        val entries = syncableSettings(app(), reader()).associateBy { it.key }

        // Typography is the obvious half, and the prefix carries it.
        for ((key, entry) in entries) {
            if (key.startsWith("reader.")) {
                assertTrue("$key should be held back under an open book", entry.affectsOpenBook)
            }
        }

        // Scrolling is not typography and is not named like it, but
        // turning it on rebuilds the page under the reader all the same.
        assertTrue(entries.getValue("app.scroll_mode").affectsOpenBook)

        // And the account-wide ones are not.
        assertFalse(entries.getValue("app.resume_last_book").affectsOpenBook)
    }

    private suspend fun configure(a: AppSettingsRepository, r: ReaderPreferencesRepository) {
        r.setFontSize(1.5)
        r.setLineHeight(1.6)
        r.setPageMargins(1.3)
        r.setHyphens(true)
        r.setLetterSpacing(0.05)
        r.setWordSpacing(0.1)
        r.setParagraphSpacing(0.4)
        r.setTextAlign(ReaderTextAlign.JUSTIFIED)
        r.setFontWeight(ReaderFontWeight.LIGHT)
        a.setScrollMode(true)
        a.setResumeLastBook(false)
        a.setDictionaryLookupEnabled(true)
        a.setOfferedHighlightTints(setOf(HighlightTint.BLUE.name, HighlightTint.GREEN.name))
        a.setHighlightDefaultTint(HighlightTint.BLUE)
    }
}
