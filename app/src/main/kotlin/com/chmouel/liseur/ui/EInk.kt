package com.chmouel.liseur.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chmouel.liseur.data.settings.EInkMode

/**
 * Whether the app should draw as if the screen were electronic paper.
 *
 * Defaults to false, which is what every screen already assumed, so
 * nothing that reads it behaves differently until something says
 * otherwise.
 */
val LocalEInk = compositionLocalOf { false }

/**
 * Makers whose Android devices have an e-paper screen.
 *
 * A list of names is a poor way to know what a screen is made of, and it
 * will be out of date the day someone ships a reader that is not on it.
 * It is here because Android offers nothing better: there is no platform
 * feature or display flag that says "this panel refreshes in a tenth of
 * a second and ghosts". The list is therefore a guess that gets the
 * common cases right, and the reason the setting it feeds keeps a manual
 * override rather than trusting itself.
 */
private val EINK_MAKERS = listOf(
    "onyx",
    "boox",
    "tolino",
    "rakuten kobo",
    "pocketbook",
    "bigme",
    "boyue",
    "meebook",
    "remarkable",
    "supernote",
    "ratta",
    "hanvon",
    "dasung",
    "xiaomi_moaan",
    "moaan",
)

/**
 * System features Onyx and others declare on their e-paper devices.
 *
 * Worth asking first: a device that says so itself is a better answer
 * than a name that merely looks familiar.
 */
private val EINK_FEATURES = listOf(
    "android.hardware.type.eink",
    "eink",
    "com.onyx.eink",
    "onyx.hardware.eink",
)

/** Whether this looks like an e-paper device. Pure, so it can be tested. */
fun isEInkDevice(
    manufacturer: String,
    brand: String,
    model: String,
    device: String,
    hasFeature: (String) -> Boolean,
): Boolean {
    if (EINK_FEATURES.any(hasFeature)) return true
    val names = listOf(manufacturer, brand, model, device).map { it.lowercase() }
    return EINK_MAKERS.any { maker -> names.any { it.contains(maker) } }
}

/** Whether the device running this build looks like e-paper. */
fun isEInkDevice(packageManager: PackageManager): Boolean = isEInkDevice(
    manufacturer = Build.MANUFACTURER.orEmpty(),
    brand = Build.BRAND.orEmpty(),
    model = Build.MODEL.orEmpty(),
    device = Build.DEVICE.orEmpty(),
    hasFeature = packageManager::hasSystemFeature,
)

/** Settles [mode] against the device and puts the answer in [LocalEInk]. */
@Composable
fun ProvideEInk(mode: EInkMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val detected = remember(context) { isEInkDevice(context.packageManager) }
    CompositionLocalProvider(LocalEInk provides mode.resolve(detected), content = content)
}
