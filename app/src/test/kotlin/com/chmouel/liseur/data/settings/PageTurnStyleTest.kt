package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three page turns, as they are stored and as a store written
 * before there were three of them reads back.
 */
class PageTurnStyleTest {

    @Test
    fun `every style is named by an id that reads back to it`() {
        PageTurnStyle.entries.forEach { style ->
            assertEquals(style, PageTurnStyle.fromId(style.id))
        }
    }

    @Test
    fun `a name from nowhere is the lifted page`() {
        assertEquals(PageTurnStyle.LIFT, PageTurnStyle.fromId(null))
        assertEquals(PageTurnStyle.LIFT, PageTurnStyle.fromId("flip"))
    }

    @Test
    fun `a store that never had the setting turns pages the way it always did`() {
        assertEquals(PageTurnStyle.LIFT, pageTurnStyleFrom(stored = null, legacyAnimation = null))
    }

    @Test
    fun `an old store with the animation off keeps the instant jump`() {
        assertEquals(PageTurnStyle.NONE, pageTurnStyleFrom(stored = null, legacyAnimation = false))
    }

    @Test
    fun `an old store with the animation on gets the lifted page`() {
        assertEquals(PageTurnStyle.LIFT, pageTurnStyleFrom(stored = null, legacyAnimation = true))
    }

    /**
     * A newer version wrote a style this one has never heard of, and the
     * old boolean is still lying beside it. Reading the boolean instead
     * would let this build quietly overwrite the newer choice with an
     * answer nobody gave.
     */
    @Test
    fun `a style this version does not know still beats the old boolean`() {
        assertEquals(
            PageTurnStyle.LIFT,
            pageTurnStyleFrom(stored = "curl", legacyAnimation = false),
        )
    }

    @Test
    fun `the stored style wins over the old boolean`() {
        assertEquals(
            PageTurnStyle.SLIDE,
            pageTurnStyleFrom(stored = PageTurnStyle.SLIDE.id, legacyAnimation = false),
        )
    }
}
