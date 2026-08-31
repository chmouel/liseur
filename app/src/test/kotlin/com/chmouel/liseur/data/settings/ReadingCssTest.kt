package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stylesheet Readium will render a book with.
 *
 * This mirrors code that is `internal` to Readium — `Layout.from`,
 * `EpubSettingsResolver`, and the `isCjk` and `isRtl` extensions — so
 * these cases are the only thing standing between a Readium upgrade and
 * a settings sheet that quietly starts describing the wrong page. If one
 * of them fails after a version bump, the mirror has drifted; re-read
 * the originals rather than adjusting the expectation.
 *
 * No navigator anywhere: the classification is a function of publication
 * metadata, which is what lets it exist before the navigator does.
 */
class ReadingCssTest {

    private fun cssFor(
        reflowable: Boolean = true,
        language: String? = null,
        metadataRtl: Boolean? = null,
    ) = readingCssFor(reflowable, language, metadataRtl)

    @Test
    fun `an ordinary book gets the default stylesheet`() {
        assertEquals(ReadingCss.Default, cssFor(language = "en"))
        assertEquals(ReadingCss.Default, cssFor(language = "fr", metadataRtl = false))
    }

    @Test
    fun `a fixed layout book is asked nothing else`() {
        // Readium gates every one of these settings on a reflowable
        // publication, so the answer cannot depend on the language.
        for (language in listOf(null, "en", "ja", "ar")) {
            assertEquals(
                ReadingCss.Unsupported,
                cssFor(reflowable = false, language = language, metadataRtl = true),
            )
        }
    }

    @Test
    fun `CJK languages get the CJK stylesheet`() {
        for (code in listOf("ja", "JA", "ko", "zh", "zh-Hans", "ZH-Hant", "zh-TW", "zh_TW")) {
            assertEquals("language $code", ReadingCss.Cjk, cssFor(language = code))
        }
    }

    @Test
    fun `a regional Japanese or Korean tag is not CJK to Readium`() {
        // Readium compares `ja` and `ko` against the whole code and only
        // strips the region for `zh`, so `ja-JP` misses. That is a bug
        // in Readium and this mirrors it deliberately: the sheet has to
        // describe the page Readium will actually produce, not the one
        // it ought to. Fix it upstream, not here.
        assertEquals(ReadingCss.Default, cssFor(language = "ja-JP"))
        assertEquals(ReadingCss.Default, cssFor(language = "ko-KR"))
    }

    @Test
    fun `right-to-left languages get the RTL stylesheet`() {
        for (code in listOf("ar", "AR", "fa", "he")) {
            assertEquals("language $code", ReadingCss.Rtl, cssFor(language = code))
        }
    }

    @Test
    fun `a regional Arabic tag is not right-to-left to Readium either`() {
        // Same mirrored quirk, in `isRtl` this time: the whole code is
        // compared against a fixed list.
        assertEquals(ReadingCss.Default, cssFor(language = "ar-EG"))
    }

    @Test
    fun `declared progression outranks the language it is declared in`() {
        assertEquals(ReadingCss.Rtl, cssFor(language = "en", metadataRtl = true))
        assertEquals(ReadingCss.Default, cssFor(language = "ar", metadataRtl = false))
    }

    @Test
    fun `language outranks progression for CJK`() {
        // Layout.from tests the language before the progression, so a
        // horizontal Japanese book reads left to right and still gets
        // the CJK sheet — which is exactly why this is not named after
        // a writing direction.
        assertEquals(ReadingCss.Cjk, cssFor(language = "ja", metadataRtl = false))
        assertEquals(ReadingCss.Cjk, cssFor(language = "zh-Hant", metadataRtl = true))
    }

    @Test
    fun `traditional Chinese is CJK and never RTL`() {
        // Readium's Language.isRtl lists zh-hant and zh-tw, and the
        // mirror in ReaderPrefs keeps them so it stays an exact copy.
        // Layout.from tests CJK first, so those books get the CJK sheet
        // whatever their progression says. This is what makes keeping
        // the two tags in the mirror harmless; if a refactor ever
        // reordered the branches, this is what would fail.
        for (code in listOf("zh-Hant", "ZH-HANT", "zh-TW", "zh_TW")) {
            assertEquals(ReadingCss.Cjk, cssFor(language = code))
            assertEquals(ReadingCss.Cjk, cssFor(language = code, metadataRtl = true))
            assertEquals(ReadingCss.Cjk, cssFor(language = code, metadataRtl = false))
        }
    }

    @Test
    fun `a book that says nothing about itself gets the default`() {
        for (code in listOf(null, "", "   ", "-", "not a language")) {
            assertEquals(ReadingCss.Default, cssFor(language = code))
        }
    }

    @Test
    fun `alignment survives into a right-to-left book and dies in a CJK one`() {
        // The distinction the whole three-state split exists for: the
        // RTL stylesheet carries a textAlign rule and the CJK ones do
        // not.
        assertTrue(ReadingCss.Default.honoursAlignment)
        assertTrue(ReadingCss.Rtl.honoursAlignment)
        assertFalse(ReadingCss.Cjk.honoursAlignment)
        assertFalse(ReadingCss.Unsupported.honoursAlignment)
    }

    @Test
    fun `hyphens and letter and word spacing are the default stylesheet's alone`() {
        assertTrue(ReadingCss.Default.honoursLatinSpacing)
        assertFalse(ReadingCss.Rtl.honoursLatinSpacing)
        assertFalse(ReadingCss.Cjk.honoursLatinSpacing)
        assertFalse(ReadingCss.Unsupported.honoursLatinSpacing)
    }

    @Test
    fun `the settings screen claims nothing it cannot know`() {
        // No book is open there, so every row stays usable.
        assertTrue(ReadingCss.Unknown.honoursAnything)
        assertTrue(ReadingCss.Unknown.honoursAlignment)
        assertTrue(ReadingCss.Unknown.honoursLatinSpacing)
    }
}
