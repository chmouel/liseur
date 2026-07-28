package com.chmouel.liseur.reader.chrome

import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Reader tap zones:
 *
 * ```
 * ┌───────────────────────────┐
 * │          chrome           │  ← top strip reveals the menu
 * ├─────────┬────────┬────────┤
 * │         │        │        │
 * │  back   │ chrome │forward │  ← center also reveals the menu
 * │         │        │        │
 * └─────────┴────────┴────────┘
 * ```
 *
 * Tapping the top strip or the center of the page gently reveals the
 * chrome (menu), as fullscreen reading apps do; the left side goes
 * back a page and the rest goes forward. When the chrome is showing,
 * any tap on the page dismisses it. Page turns are delegated to
 * [onTurnPage] so they can run the page-turn effect.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderTapZones(
    private val navigator: OverflowableNavigator,
    private val isChromeVisible: () -> Boolean,
    private val onTurnPage: (forward: Boolean) -> Unit,
    private val onShowChrome: () -> Unit,
    private val onHideChrome: () -> Unit,
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        if (isChromeVisible()) {
            onHideChrome()
            return true
        }

        val width = navigator.publicationView.width.toFloat()
        val height = navigator.publicationView.height.toFloat()
        if (width <= 0f || height <= 0f) return false

        val rtl = navigator.overflow.value.readingProgression == ReadingProgression.RTL
        val x = event.point.x / width
        val y = event.point.y / height
        when {
            y < CHROME_ZONE || (x in CHROME_X && y in CHROME_Y) -> onShowChrome()

            x < BACK_ZONE -> onTurnPage(rtl)
            else -> onTurnPage(!rtl)
        }
        return true
    }

    companion object {
        /** Top strip of the screen that reveals the chrome. */
        const val CHROME_ZONE = 0.14f

        /** Center box of the page that also reveals the chrome. */
        val CHROME_X = 0.3f..0.7f
        val CHROME_Y = 0.3f..0.7f

        /** Left portion of the screen that turns back a page. */
        const val BACK_ZONE = 0.3f
    }
}
