package com.chmouel.liseur.reader.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOverlaysTest {

    @Test
    fun `idle state returns None`() {
        val state = resolveGestureHudState(
            pinchRefused = false,
            pinchTarget = null,
            brightnessSwiping = false,
            brightnessPreview = null,
        )
        assertEquals(GestureHudState.None, state)
    }

    @Test
    fun `pinch refused takes priority over pinch target and brightness`() {
        val state = resolveGestureHudState(
            pinchRefused = true,
            pinchTarget = 1.5,
            brightnessSwiping = true,
            brightnessPreview = 0.4f,
        )
        assertEquals(GestureHudState.FixedLayoutPinch, state)
    }

    @Test
    fun `pinch font size takes priority over brightness`() {
        val state = resolveGestureHudState(
            pinchRefused = false,
            pinchTarget = 1.25,
            brightnessSwiping = true,
            brightnessPreview = 0.6f,
        )
        assertEquals(GestureHudState.FontSize(1.25), state)
    }

    @Test
    fun `brightness swiping with preview returns Brightness`() {
        val state = resolveGestureHudState(
            pinchRefused = false,
            pinchTarget = null,
            brightnessSwiping = true,
            brightnessPreview = 0.75f,
        )
        assertEquals(GestureHudState.Brightness(0.75f), state)
    }

    @Test
    fun `brightness swiping with null preview returns None`() {
        val state = resolveGestureHudState(
            pinchRefused = false,
            pinchTarget = null,
            brightnessSwiping = true,
            brightnessPreview = null,
        )
        assertEquals(GestureHudState.None, state)
    }

    @Test
    fun `non-swiping brightness preview is ignored`() {
        val state = resolveGestureHudState(
            pinchRefused = false,
            pinchTarget = null,
            brightnessSwiping = false,
            brightnessPreview = 0.5f,
        )
        assertEquals(GestureHudState.None, state)
    }

    @Test
    fun `baseline profile exists and contains valid rules`() {
        val file = sequenceOf(
            File("src/main/baseline-prof.txt"),
            File("app/src/main/baseline-prof.txt"),
        ).firstOrNull { it.isFile }
        assertTrue("baseline-prof.txt must exist", file != null)
        val rules = file!!.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("baseline-prof.txt must contain at least one rule", rules.isNotEmpty())
        val pattern = Regex("^[HSPL]+Lcom/chmouel/liseur/.*")
        for (rule in rules) {
            assertTrue("Rule '$rule' does not match expected pattern", pattern.matches(rule))
        }
    }
}
