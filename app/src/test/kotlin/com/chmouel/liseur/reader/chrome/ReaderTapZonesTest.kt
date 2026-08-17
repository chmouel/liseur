package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.reader.chrome.ReaderTapZones.Companion.zoneAt
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zones are checked at real screen sizes rather than in the
 * abstract, because the whole point of the ceilings is that they change
 * nothing on a phone and a great deal on a tablet.
 */
class ReaderTapZonesTest {

    private data class Screen(val w: Float, val h: Float, val density: Float)

    /** Pixel-class phone: 411 x 914 dp. */
    private val phone = Screen(1080f, 2400f, 2.625f)

    /** Boox Go 7: 1264 x 1680 at 300dpi, so 674 x 896 dp. */
    private val eink = Screen(1264f, 1680f, 1.875f)

    /** 13" tablet held sideways: 1480 x 924 dp. */
    private val tablet = Screen(2960f, 1848f, 2f)

    /** The widest window that still counts as compact: 599 x 1000 dp. */
    private val wideCompact = Screen(1198f, 2000f, 2f)

    private fun Screen.at(fx: Float, fy: Float) =
        zoneAt(w * fx, h * fy, w, h, density)

    private fun Screen.scrolledAt(fx: Float, fy: Float) =
        zoneAt(w * fx, h * fy, w, h, density, scrolling = true)

    @Test
    fun `phone zones are exactly what they always were`() {
        // Just inside the old 14% strip.
        assertEquals(Zone.CHROME, phone.at(0.5f, 0.13f))
        assertEquals(Zone.CHROME, phone.at(0.5f, 0.5f))
        // The old centre box ran 0.3..0.7 on both axes.
        assertEquals(Zone.CHROME, phone.at(0.31f, 0.31f))
        assertEquals(Zone.CHROME, phone.at(0.69f, 0.69f))
        assertEquals(Zone.BACK, phone.at(0.1f, 0.8f))
        assertEquals(Zone.FORWARD, phone.at(0.9f, 0.8f))
    }

    @Test
    fun `phone sides outside the box still turn pages`() {
        assertEquals(Zone.BACK, phone.at(0.2f, 0.5f))
        assertEquals(Zone.FORWARD, phone.at(0.8f, 0.5f))
    }

    @Test
    fun `a tablet centre box is narrower than four tenths of the screen`() {
        // 0.4 of 1480dp would be 592dp of dead centre; capped at 200dp it
        // stops well before two thirds of the way across.
        assertEquals(Zone.FORWARD, tablet.at(0.62f, 0.5f))
        assertEquals(Zone.BACK, tablet.at(0.25f, 0.5f))
        // The middle is still the middle.
        assertEquals(Zone.CHROME, tablet.at(0.5f, 0.5f))
    }

    @Test
    fun `an e-ink reader keeps a reachable centre`() {
        assertEquals(Zone.CHROME, eink.at(0.5f, 0.5f))
        assertEquals(Zone.FORWARD, eink.at(0.68f, 0.5f))
    }

    @Test
    fun `the ceilings never bind below the medium width`() {
        // 599dp wide: the fractions come to a 239.6 x 400dp centre box,
        // both over the ceilings. A compact window keeps them anyway, so
        // a tap that used to open the chrome still does.
        assertEquals(Zone.CHROME, wideCompact.at(0.31f, 0.31f))
        assertEquals(Zone.CHROME, wideCompact.at(0.69f, 0.69f))
        assertEquals(Zone.CHROME, wideCompact.at(0.5f, 0.13f))
    }

    @Test
    fun `reading direction is not this function's business`() {
        // zoneAt reports sides of the screen; the caller maps them onto
        // forward and back. Left is always BACK here, even though an RTL
        // book turns it into a forward page.
        assertEquals(Zone.BACK, phone.at(0.05f, 0.9f))
    }

    @Test
    fun `a scrolled book has no side to turn`() {
        // Every corner and edge of every screen: there is no page to
        // turn, so a tap can only mean the menu.
        for (screen in listOf(phone, eink, tablet, wideCompact)) {
            for (fx in listOf(0.02f, 0.25f, 0.5f, 0.75f, 0.98f)) {
                for (fy in listOf(0.02f, 0.25f, 0.5f, 0.75f, 0.98f)) {
                    assertEquals(Zone.CHROME, screen.scrolledAt(fx, fy))
                }
            }
        }
    }
}
