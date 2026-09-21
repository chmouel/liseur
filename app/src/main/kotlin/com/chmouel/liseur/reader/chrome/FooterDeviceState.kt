package com.chmouel.liseur.reader.chrome

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chmouel.liseur.data.settings.FooterField
import com.chmouel.liseur.reader.progress.FooterDevice
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.delay
import java.util.Date

/**
 * What the device has to say to the reading footer, for the two fields
 * that are not about the book.
 *
 * Neither reading is subscribed to. The battery broadcasts a change
 * every time a milliamp moves and the clock could be watched by the
 * second, and a footer that repaints on either is a footer repainting
 * for no reader's benefit. Both are read when the footer is drawn, and
 * a ticker nudges the drawing once a minute, which is as often as
 * either figure changes on the screen.
 *
 * The ticker only runs while the reader is resumed, since a minute
 * spent behind another app is a minute of writing a figure nobody is
 * looking at, and it takes a reading on the way back so the corner is
 * current again the moment the book is.
 *
 * Electronic paper gets no ticker at all. Every repaint there leaves a
 * ghost of the last one, and a clock that rewrites the corner of a
 * still page once a minute would leave a column of them. The figures
 * are read when the page is, so they come up to date on a page turn —
 * a little stale on a page held open for a while, which is the cheaper
 * of the two faults on a screen that shows its history. [turn] is what
 * makes that true of every book: a turn usually redraws the footer by
 * moving the position under it, and [turn] covers the book that has no
 * positions to move.
 *
 * Nothing is read at all when no slot asked for it, so a footer
 * showing neither field registers nothing and wakes nothing.
 */
@Composable
fun rememberFooterDevice(
    wantsClock: Boolean,
    wantsBattery: Boolean,
    turn: Int,
): FooterDevice {
    if (!wantsClock && !wantsBattery) return FooterDevice()
    val context = LocalContext.current
    val eInk = LocalEInk.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (!eInk) {
        // Only while the reader is in front of someone. A minute
        // ticker left running behind another app would wake to write
        // a clock nobody is reading, and the first turn of it on the
        // way back is what makes the figure current again after a
        // spell away.
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                tick++
                while (true) {
                    delay(millisToNextMinute())
                    tick++
                }
            }
        }
    }
    // Two things ask for a fresh reading: a minute going by and a page
    // turning. Neither count means anything, and they are added only
    // as a way of depending on both, since the readings below are
    // taken at composition and something has to bring one about.
    return deviceAt(tick + turn, context, wantsClock, wantsBattery)
}

private fun deviceAt(
    @Suppress("UNUSED_PARAMETER") nudge: Int,
    context: Context,
    wantsClock: Boolean,
    wantsBattery: Boolean,
): FooterDevice {
    val battery = if (wantsBattery) context.batteryNow() else null
    return FooterDevice(
        nowMillis = if (wantsClock) System.currentTimeMillis() else null,
        batteryPercent = battery?.first,
        charging = battery?.second ?: false,
    )
}

/**
 * A reading taken for one field, on the spot.
 *
 * [rememberFooterDevice] holds only what the slots are showing, which
 * is right for drawing and wrong for the question the note asks: is
 * there anything to show in the field this tap is about to choose? A
 * clock nobody is showing yet is not a clock that cannot be shown. The
 * note is raised from a tap rather than from a composition, so it can
 * pay for the look.
 */
fun footerDeviceFor(field: FooterField, context: Context): FooterDevice =
    deviceAt(
        nudge = 0,
        context = context,
        wantsClock = field == FooterField.CLOCK,
        wantsBattery = field == FooterField.BATTERY,
    )

/**
 * The charge left and whether it is going up, or null from a device
 * that will not say.
 *
 * `BATTERY_PROPERTY_CAPACITY` is asked of the manager rather than read
 * off the sticky `ACTION_BATTERY_CHANGED` broadcast: the broadcast
 * carries the same number and a registration to go with it, and the
 * footer wants the number.
 */
private fun Context.batteryNow(): Pair<Int, Boolean>? {
    val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level !in 0..100) return null
    return level to manager.isCharging
}

/**
 * How long until the minute turns over.
 *
 * Sleeping a whole minute from an arbitrary moment would show each
 * time for a minute but change it up to a minute late; waiting for the
 * boundary puts the change where the clock puts it. The floor keeps a
 * boundary that has just gone past from spinning.
 */
private fun millisToNextMinute(): Long {
    val past = System.currentTimeMillis() % 60_000L
    return (60_000L - past).coerceAtLeast(1_000L)
}

/** The time of day, in whichever of 12 or 24 hours the device keeps. */
@Composable
fun clockText(atMillis: Long): String {
    val context = LocalContext.current
    return remember(atMillis, context) {
        DateFormat.getTimeFormat(context).format(Date(atMillis))
    }
}
