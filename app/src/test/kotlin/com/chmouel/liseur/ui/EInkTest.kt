package com.chmouel.liseur.ui

import com.chmouel.liseur.data.settings.EInkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app decides a screen is made of.
 *
 * The maker list this walks is a guess by construction — Android has no
 * flag for "this panel ghosts" — so the value of these cases is less in
 * proving it right than in pinning what it currently answers, using
 * build strings that real devices actually report. When someone widens
 * the list, the phones below are what stops it swallowing them.
 */
class EInkTest {

    private fun noFeatures(name: String) = false

    private fun looksLikeEInk(
        manufacturer: String,
        brand: String,
        model: String,
        device: String,
    ) = isEInkDevice(manufacturer, brand, model, device, ::noFeatures)

    @Test
    fun `an ordinary phone is not e-ink`() {
        assertFalse(looksLikeEInk("Google", "google", "Pixel 8", "shiba"))
        assertFalse(looksLikeEInk("samsung", "samsung", "SM-S918B", "dm3q"))
        assertFalse(looksLikeEInk("OnePlus", "OnePlus", "CPH2449", "OP595DL1"))
    }

    @Test
    fun `an Onyx Boox is e-ink by name`() {
        assertTrue(looksLikeEInk("ONYX", "Onyx", "Go 7", "GO7"))
        assertTrue(looksLikeEInk("ONYX", "Onyx", "GoColor7", "GoColor7"))
        assertTrue(looksLikeEInk("ONYX", "Onyx", "Page", "Page"))
        assertTrue(looksLikeEInk("ONYX", "Onyx", "Note Air4 C", "NoteAir4C"))
        assertTrue(looksLikeEInk("ONYX", "Onyx", "Tab Ultra C", "TabUltraC"))
    }

    @Test
    fun `the other makers are recognised too`() {
        assertTrue(looksLikeEInk("Rakuten Kobo", "kobo", "Kobo Elipsa", "elipsa"))
        assertTrue(looksLikeEInk("Tolino", "tolino", "tolino epos 3", "epos3"))
        assertTrue(looksLikeEInk("Obreey", "pocketbook", "PocketBook 740", "pb740"))
        assertTrue(looksLikeEInk("reMarkable", "remarkable", "reMarkable 2", "rm2"))
        assertTrue(looksLikeEInk("Meebook", "meebook", "M6", "m6"))
        assertTrue(looksLikeEInk("BIGME", "bigme", "HiBreak Pro", "hibreakpro"))
        assertTrue(looksLikeEInk("Boyue", "boyue", "Likebook P78", "p78"))
        assertTrue(looksLikeEInk("Ratta", "supernote", "A5X", "a5x"))
        assertTrue(looksLikeEInk("Dasung", "dasung", "Paperlike", "paperlike"))
    }

    @Test
    fun `a name is matched wherever the maker chose to put it`() {
        // Some devices carry the maker only in the model or the codename;
        // an empty manufacturer must not be the end of the question.
        assertTrue(looksLikeEInk("", "", "Boox Poke 5", ""))
        assertTrue(looksLikeEInk("", "", "", "tolino_shine"))
    }

    @Test
    fun `a device that declares the feature is taken at its word`() {
        for (feature in listOf("android.hardware.type.eink", "eink", "com.onyx.eink")) {
            assertTrue(
                isEInkDevice(
                    manufacturer = "Nobody",
                    brand = "nobody",
                    model = "Unknown",
                    device = "unknown",
                    hasFeature = { it == feature },
                ),
            )
        }
    }

    @Test
    fun `auto follows the device`() {
        assertTrue(EInkMode.AUTO.resolve(deviceLooksLikeEInk = true))
        assertFalse(EInkMode.AUTO.resolve(deviceLooksLikeEInk = false))
    }

    @Test
    fun `the manual settings overrule the guess`() {
        assertTrue(EInkMode.ON.resolve(deviceLooksLikeEInk = false))
        assertTrue(EInkMode.ON.resolve(deviceLooksLikeEInk = true))
        assertFalse(EInkMode.OFF.resolve(deviceLooksLikeEInk = true))
        assertFalse(EInkMode.OFF.resolve(deviceLooksLikeEInk = false))
    }

    @Test
    fun `an unknown id falls back to asking the device`() {
        assertTrue(EInkMode.fromId(null) == EInkMode.AUTO)
        assertTrue(EInkMode.fromId("nonsense") == EInkMode.AUTO)
        assertTrue(EInkMode.fromId("on") == EInkMode.ON)
        assertTrue(EInkMode.fromId("off") == EInkMode.OFF)
    }
}
