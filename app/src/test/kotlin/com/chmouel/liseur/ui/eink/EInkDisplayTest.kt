package com.chmouel.liseur.ui.eink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing which vendor controller to talk to.
 *
 * The reflection itself cannot be exercised anywhere but on the devices,
 * which is exactly why the deciding is kept out of it: the order, the
 * requirement that both classes be present, and the answer when none of
 * them are, are all questions about a list, and a list can be asked them
 * here.
 */
class EInkDisplayTest {

    private fun present(vararg names: String): (String) -> Boolean {
        val set = names.toSet()
        return { it in set }
    }

    @Test
    fun `a device with none of the classes gets no controller`() {
        assertNull(firstAvailableShape(ONYX_SHAPES, present()))
    }

    @Test
    fun `the framework spelling is found when the SDK one is absent`() {
        val shape = firstAvailableShape(
            ONYX_SHAPES,
            present("android.onyx.EpdController", "android.onyx.UpdateMode"),
        )
        assertEquals("android.onyx.EpdController", shape?.controllerClass)
        assertEquals("Onyx", shape?.vendor)
    }

    @Test
    fun `the best known shape wins when more than one is present`() {
        val shape = firstAvailableShape(ONYX_SHAPES) { true }
        assertEquals(ONYX_SHAPES.first().controllerClass, shape?.controllerClass)
    }

    @Test
    fun `a controller without its update mode enum is not usable`() {
        // Half a shape is no shape: every method worth calling takes the
        // enum, so a controller found without it can be asked nothing.
        assertNull(
            firstAvailableShape(
                ONYX_SHAPES,
                present("com.onyx.android.sdk.api.device.epd.EpdController"),
            ),
        )
    }

    @Test
    fun `an update mode enum without its controller is not usable`() {
        assertNull(
            firstAvailableShape(
                ONYX_SHAPES,
                present("com.onyx.android.sdk.api.device.epd.UpdateMode"),
            ),
        )
    }

    @Test
    fun `every shape names both classes`() {
        // A blank name would be asked of the class loader and fail the
        // same way a real absence does, so a shape carrying one would be
        // a guess that can never match and never say so.
        ONYX_SHAPES.forEach {
            assertTrue(it.controllerClass.isNotBlank())
            assertTrue(it.updateModeClass.isNotBlank())
            assertTrue(it.vendor.isNotBlank())
        }
    }

    @Test
    fun `binding where no vendor classes exist yields the absent display`() {
        // The JVM running these tests is the best available stand-in for
        // every device that is not a Boox, which is nearly all of them.
        val display = OnyxEInkDisplay.bind()
        assertNull(display.vendor)
        assertEquals(EInkDisplay.Absent, display)
    }

    @Test
    fun `the absent display answers every call and does nothing`() {
        val absent = EInkDisplay.Absent
        absent.release()
        assertNull(absent.vendor)
    }
}
