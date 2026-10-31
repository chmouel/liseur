package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.TapLayout
import com.chmouel.liseur.data.settings.TapSideWidth
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Action
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Companion.actionAt
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Companion.endpaperLayout
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Companion.forward
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Companion.zoneAt
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun Screen.actionAt(
        fx: Float,
        fy: Float,
        zones: TapZones,
        sideWidth: Int = TapSideWidth.WIDE,
        rtl: Boolean = false,
        scrolling: Boolean = false,
    ) = actionAt(w * fx, h * fy, w, h, density, scrolling, TapLayout(zones, sideWidth), rtl)

    private val back = Action.Turn(forward = false)
    private val ahead = Action.Turn(forward = true)

    @Test
    fun `both sides is the layout the app always had`() {
        assertEquals(false, forward(Zone.BACK, rtl = false))
        assertEquals(true, forward(Zone.FORWARD, rtl = false))
        assertEquals(back, phone.actionAt(0.1f, 0.8f, TapZones.BOTH))
        assertEquals(ahead, phone.actionAt(0.9f, 0.8f, TapZones.BOTH))
        assertEquals(Action.Chrome, phone.actionAt(0.5f, 0.5f, TapZones.BOTH))
    }

    @Test
    fun `a right-to-left book turns forward on the left with both sides`() {
        assertEquals(true, forward(Zone.BACK, rtl = true))
        assertEquals(false, forward(Zone.FORWARD, rtl = true))
        assertNull(forward(Zone.CHROME, rtl = true))
        assertEquals(ahead, phone.actionAt(0.1f, 0.8f, TapZones.BOTH, rtl = true))
    }

    @Test
    fun `one side goes back above its middle and forward below it`() {
        for (rtl in listOf(false, true)) {
            assertEquals(back, phone.actionAt(0.1f, 0.4f, TapZones.LEFT, rtl = rtl))
            assertEquals(ahead, phone.actionAt(0.1f, 0.8f, TapZones.LEFT, rtl = rtl))
            assertEquals(Action.Chrome, phone.actionAt(0.9f, 0.8f, TapZones.LEFT, rtl = rtl))
            assertEquals(back, phone.actionAt(0.9f, 0.4f, TapZones.RIGHT, rtl = rtl))
            assertEquals(ahead, phone.actionAt(0.9f, 0.8f, TapZones.RIGHT, rtl = rtl))
            assertEquals(Action.Chrome, phone.actionAt(0.1f, 0.8f, TapZones.RIGHT, rtl = rtl))
        }
    }

    @Test
    fun `one wide side is its whole half outside the centre box`() {
        // Both sides' Wide puts back at under 30%, so 40% is forward
        // there; with the left side alone it is still the left side.
        assertEquals(ahead, phone.actionAt(0.4f, 0.8f, TapZones.BOTH))
        assertEquals(ahead, phone.actionAt(0.4f, 0.8f, TapZones.LEFT))
        assertEquals(Action.Chrome, phone.actionAt(0.6f, 0.8f, TapZones.LEFT))
        assertEquals(ahead, phone.actionAt(0.6f, 0.8f, TapZones.RIGHT))
        assertEquals(Action.Chrome, phone.actionAt(0.4f, 0.8f, TapZones.RIGHT))
        // Either side of the centre box, which still opens the menu.
        assertEquals(Action.Chrome, phone.actionAt(0.4f, 0.5f, TapZones.LEFT))
        assertEquals(back, phone.actionAt(0.25f, 0.5f, TapZones.LEFT))
        assertEquals(Action.Chrome, phone.actionAt(0.6f, 0.5f, TapZones.RIGHT))
        assertEquals(ahead, phone.actionAt(0.75f, 0.6f, TapZones.RIGHT))
    }

    @Test
    fun `one narrow side is only that edge`() {
        assertEquals(ahead, phone.actionAt(0.1f, 0.8f, TapZones.LEFT, sideWidth = 15))
        assertEquals(Action.Chrome, phone.actionAt(0.2f, 0.8f, TapZones.LEFT, sideWidth = 15))
        assertEquals(ahead, phone.actionAt(0.9f, 0.8f, TapZones.RIGHT, sideWidth = 15))
        assertEquals(Action.Chrome, phone.actionAt(0.8f, 0.8f, TapZones.RIGHT, sideWidth = 15))
    }

    @Test
    fun `the split follows the top strip on a capped tablet`() {
        // The split sits halfway down what is left under the top strip,
        // so it rises when a wide window caps the strip.
        val portraitTablet = Screen(1848f, 2960f, 2f)
        assertEquals(back, phone.actionAt(0.05f, 0.555f, TapZones.LEFT))
        assertEquals(ahead, portraitTablet.actionAt(0.05f, 0.555f, TapZones.LEFT))
    }

    @Test
    fun `no side turns a scrolled book or under the top strip`() {
        for (zones in TapZones.entries) {
            assertEquals(Action.Chrome, phone.actionAt(0.05f, 0.8f, zones, scrolling = true))
            assertEquals(Action.Chrome, phone.actionAt(0.05f, 0.05f, zones))
        }
    }

    @Test
    fun `the off preset still opens the chrome from the middle`() {
        // Off takes the sides away, not the menu: a reader who switched
        // the sides off still needs a tap that brings the controls back.
        assertEquals(Action.Chrome, phone.actionAt(0.5f, 0.5f, TapZones.OFF))
        assertEquals(Action.Inert, phone.actionAt(0.1f, 0.8f, TapZones.OFF))
        assertEquals(Action.Inert, phone.actionAt(0.9f, 0.8f, TapZones.OFF, rtl = true))
    }

    @Test
    fun `the endpaper turns back even with the sides off`() {
        val off = TapLayout(TapZones.OFF, 15)
        assertEquals(TapLayout(TapZones.BOTH, 15), endpaperLayout(off))
        val left = TapLayout(TapZones.LEFT, 15)
        assertEquals(left, endpaperLayout(left))
    }

    private fun Screen.narrowAt(fx: Float, fy: Float, percent: Int) =
        zoneAt(w * fx, h * fy, w, h, density, sideWidth = percent)

    @Test
    fun `narrow sides leave the whole middle band to the chrome`() {
        assertEquals(Zone.BACK, phone.narrowAt(0.1f, 0.8f, 15))
        assertEquals(Zone.CHROME, phone.narrowAt(0.2f, 0.8f, 15))
        // Below the old centre box, which used to turn forward.
        assertEquals(Zone.CHROME, phone.narrowAt(0.8f, 0.8f, 15))
        assertEquals(Zone.FORWARD, phone.narrowAt(0.9f, 0.8f, 15))
        // The top strip wins over a side.
        assertEquals(Zone.CHROME, phone.narrowAt(0.05f, 0.05f, 15))
    }

    @Test
    fun `narrow sides are the same share of a tablet as of a phone`() {
        for (screen in listOf(phone, eink, tablet)) {
            assertEquals(Zone.BACK, screen.narrowAt(0.09f, 0.5f, 10))
            assertEquals(Zone.CHROME, screen.narrowAt(0.11f, 0.5f, 10))
            assertEquals(Zone.CHROME, screen.narrowAt(0.89f, 0.5f, 10))
            assertEquals(Zone.FORWARD, screen.narrowAt(0.91f, 0.5f, 10))
        }
    }

    @Test
    fun `the wide stop is the layout the app always had`() {
        for (screen in listOf(phone, eink, tablet, wideCompact)) {
            for (fx in listOf(0.05f, 0.25f, 0.5f, 0.65f, 0.95f)) {
                for (fy in listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)) {
                    assertEquals(
                        screen.at(fx, fy),
                        screen.narrowAt(fx, fy, TapSideWidth.WIDE),
                    )
                }
            }
        }
    }
}
