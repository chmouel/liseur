package com.chmouel.liseur.ui.eink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Binding to a vendor controller that is not there, or is there wrong.
 *
 * No real Onyx class can be reached from a build, so these point the
 * binder at [FakeController] and its variously broken siblings instead.
 * That covers everything the binder decides — the order it tries shapes
 * in, what it insists on finding before it claims a vendor, and what it
 * does when a call fails — and leaves exactly one thing untested, which
 * is whether Onyx's real classes are spelled the way [ONYX_SHAPES]
 * guesses. Only a device can answer that, which is why the setting says
 * out loud what it bound.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class EInkDisplayTest {

    // Real views, because the calls under test take them. They are handed
    // straight to a fake that ignores them; Robolectric is here only so
    // that "a view" is something this JVM can produce at all.
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view by lazy { View(context) }
    private val webView by lazy { WebView(context) }

    private val pkg = "com.chmouel.liseur.ui.eink"

    private fun shape(controller: String, mode: String = "FakeUpdateMode") =
        EInkVendorShape(
            vendor = "Fake",
            controllerClass = "$pkg.$controller",
            updateModeClass = "$pkg.$mode",
        )

    private fun bind(vararg shapes: EInkVendorShape) =
        OnyxEInkDisplay.bind(shapes.toList(), javaClass.classLoader)

    @Before
    fun clearCounters() {
        FakeController.reset()
        FakeThrowingController.reset()
    }

    @Test
    fun `a whole controller binds and is named`() {
        val display = bind(shape("FakeController"))
        assertEquals("Fake", display.vendor)
        assertTrue(display is OnyxEInkDisplay)
    }

    @Test
    fun `a bound controller is actually called`() {
        val display = bind(shape("FakeController"))
        display.readingMode(view)
        display.optimizeWebView(webView)
        assertEquals(1, FakeController.updateModeCalls)
        assertEquals(1, FakeController.contrastCalls)
    }

    @Test
    fun `a device with none of the classes gets nothing`() {
        assertSame(EInkDisplay.Absent, bind(shape("NotAClassAnywhere")))
    }

    @Test
    fun `a controller found without its update mode enum is refused`() {
        // Half a shape is no shape: the method worth calling takes the
        // enum, so a controller found without it can be asked nothing.
        assertSame(EInkDisplay.Absent, bind(shape("FakeController", "NoSuchMode")))
    }

    @Test
    fun `an enum without REGAL is refused`() {
        // The one mode this asks for. An enum that has everything else is
        // an enum this has nothing to say to.
        assertSame(
            EInkDisplay.Absent,
            bind(shape("FakeController", "FakeModeWithoutRegal")),
        )
    }

    @Test
    fun `a controller whose methods are not static is refused`() {
        // They would be invoked with no receiver, so binding to them
        // would be claiming a vendor that fails on its first call.
        assertSame(EInkDisplay.Absent, bind(shape("FakeInstanceController")))
    }

    @Test
    fun `a controller missing one of the two methods is refused`() {
        assertSame(EInkDisplay.Absent, bind(shape("FakeHalfController")))
    }

    @Test
    fun `an unusable shape is passed over for a later one`() {
        // The point of a list: the first spelling being wrong on this
        // firmware must not cost the device the spelling that is right.
        val display = bind(
            shape("NotAClassAnywhere"),
            shape("FakeInstanceController"),
            shape("FakeHalfController"),
            shape("FakeController"),
        )
        assertEquals("Fake", display.vendor)
    }

    @Test
    fun `the first usable shape wins`() {
        val display = bind(
            shape("FakeController").copy(vendor = "First"),
            shape("FakeController").copy(vendor = "Second"),
        )
        assertEquals("First", display.vendor)
    }

    @Test
    fun `a controller that throws is retired and never asked again`() {
        val display = bind(shape("FakeThrowingController"))
        assertEquals("Fake", display.vendor)
        // It binds — everything was found — and gives up the moment it is
        // shown that finding is not the same as working. Ten attempts,
        // one call: the retirement is what is being asserted, not merely
        // that the throw does not escape.
        repeat(5) {
            display.readingMode(view)
            display.optimizeWebView(webView)
        }
        assertEquals(1, FakeThrowingController.calls)
    }

    @Test
    fun `a fatal error from the vendor is not swallowed`() {
        // It arrives wrapped in an InvocationTargetException, which is a
        // ReflectiveOperationException, so it would otherwise be filed as
        // an ordinary miss and the process left running on a dead heap.
        val display = bind(shape("FakeFatalController"))
        assertThrows(OutOfMemoryError::class.java) { display.readingMode(view) }
    }

    @Test
    fun `binding on this JVM finds no real vendor`() {
        // The JVM running these tests is the best available stand-in for
        // every device that is not a Boox, which is nearly all of them.
        assertSame(EInkDisplay.Absent, OnyxEInkDisplay.bind())
    }

    @Test
    fun `the absent display answers every call and does nothing`() {
        val absent = EInkDisplay.Absent
        absent.readingMode(view)
        absent.optimizeWebView(webView)
        assertNull(absent.vendor)
    }

    @Test
    fun `every real shape names a vendor and both classes`() {
        // A blank name would be asked of the class loader and fail the
        // same way a real absence does, so a shape carrying one would be
        // a guess that can never match and never say so.
        ONYX_SHAPES.forEach {
            assertTrue(it.controllerClass.isNotBlank())
            assertTrue(it.updateModeClass.isNotBlank())
            assertTrue(it.vendor.isNotBlank())
        }
        assertNotNull(ONYX_SHAPES.firstOrNull())
    }
}
