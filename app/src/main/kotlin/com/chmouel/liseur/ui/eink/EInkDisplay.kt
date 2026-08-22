package com.chmouel.liseur.ui.eink

import android.view.View
import android.webkit.WebView

/**
 * How the app asks a vendor's screen controller to do something.
 *
 * Ordinary Android has nothing to say about electronic paper. It draws
 * frames and assumes a panel that can show them; whether the panel then
 * ghosts, flashes, or takes a fifth of a second about it is not
 * expressible. Makers therefore ship their own controls, and Onyx's are
 * the ones this reaches for.
 *
 * [Absent] is the ordinary answer. Almost every device that runs Liseur
 * has no such controller, and some that do will spell it in a way this
 * does not recognise, so nothing here may be load-bearing: everything it
 * offers is an improvement to a screen that already works without it.
 */
interface EInkDisplay {

    /**
     * What was bound, for the settings screen to show.
     *
     * None of this can be tested from a build — the classes exist only
     * on the devices themselves — so the app says out loud what it
     * found, and the reader holding the device can see at a glance
     * whether the guess landed.
     */
    val vendor: String?

    /**
     * Asks that [view] be repainted in the mode meant for pages of text.
     *
     * Onyx calls it REGAL: a partial update tuned to leave as little of
     * the previous page behind as it can, at the cost of some flicker
     * over dark backgrounds. It is the right mode for a book and the
     * wrong one for a photograph.
     */
    fun readingMode(view: View)

    /**
     * Asks that a web view not be dropped into the crude two-tone mode
     * the firmware uses for scrolling web pages.
     *
     * A book is rendered in a web view, and prose flattened to pure
     * black and white loses every antialiased edge on every glyph.
     */
    fun optimizeWebView(view: WebView)

    /**
     * Puts back whatever was changed.
     *
     * Called when the reader is no longer in front, and on any failure.
     * Some of these settings are not the app's to keep: they are applied
     * per-application by name but take effect on the panel, and one left
     * switched on is this app's ghosting inflicted on whatever the
     * reader opens next.
     */
    fun release()

    /** The controller nobody has, which is nearly everybody. */
    object Absent : EInkDisplay {
        override val vendor: String? = null
        override fun readingMode(view: View) = Unit
        override fun optimizeWebView(view: WebView) = Unit
        override fun release() = Unit
    }
}

/**
 * A shape a vendor's controller might have on a given device.
 *
 * Onyx publishes `com.onyx.android.sdk.api.device.epd.EpdController`,
 * but publishes it as a library apps are expected to bundle — and a
 * proprietary jar would end Liseur's F-Droid eligibility, so that is not
 * a door this app can walk through. What is reachable is whatever the
 * firmware itself put on the classpath, and that is spelled differently
 * across generations of device and versions of their Android.
 *
 * So rather than one name and a hope, this is a list of guesses tried in
 * order. [updateModeClass] is named separately because the mode is an
 * enum, and an enum constant has to be resolved by name before it can be
 * passed to anything.
 */
data class EInkVendorShape(
    val vendor: String,
    val controllerClass: String,
    val updateModeClass: String,
)

/**
 * The shapes to try, best-known first.
 *
 * The first is the SDK's own spelling, which is present on devices that
 * happen to expose the SDK system-wide. The rest are framework classes
 * on Onyx's modified Android, which is what an app that bundles nothing
 * can actually reach.
 */
val ONYX_SHAPES: List<EInkVendorShape> = listOf(
    EInkVendorShape(
        vendor = "Onyx",
        controllerClass = "com.onyx.android.sdk.api.device.epd.EpdController",
        updateModeClass = "com.onyx.android.sdk.api.device.epd.UpdateMode",
    ),
    EInkVendorShape(
        vendor = "Onyx",
        controllerClass = "android.onyx.EpdController",
        updateModeClass = "android.onyx.UpdateMode",
    ),
    EInkVendorShape(
        vendor = "Onyx",
        controllerClass = "android.onyx.epd.EpdController",
        updateModeClass = "android.onyx.epd.UpdateMode",
    ),
)

/**
 * The first shape whose classes [isPresent] says are on this device.
 *
 * Kept apart from the reflection that uses it so that the choosing — the
 * part with an order, a fallback and an answer — can be tested, while
 * the part that can only be exercised on the hardware itself stays as
 * small as it can be made.
 *
 * Both classes must be there. A controller without the enum its methods
 * take is a controller nothing can be asked of.
 */
fun firstAvailableShape(
    shapes: List<EInkVendorShape>,
    isPresent: (String) -> Boolean,
): EInkVendorShape? = shapes.firstOrNull {
    isPresent(it.controllerClass) && isPresent(it.updateModeClass)
}
