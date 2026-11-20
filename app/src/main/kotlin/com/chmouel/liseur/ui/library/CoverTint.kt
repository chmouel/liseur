package com.chmouel.liseur.ui.library

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.browseCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The colour a cover is mostly made of, as packed ARGB, or null when the
 * cover is near enough grey that any tint taken from it would be mud.
 *
 * Pixels count by how saturated they are, so a small red moon on a dark
 * sky outweighs the sky, and near-black and near-white pixels, which
 * carry no hue worth keeping, do not count at all.
 */
internal fun coverTint(pixels: IntArray): Int? {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var weight = 0.0
    var counted = 0
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val pr = (pixel shr 16) and 0xFF
        val pg = (pixel shr 8) and 0xFF
        val pb = pixel and 0xFF
        val max = maxOf(pr, pg, pb)
        val min = minOf(pr, pg, pb)
        counted++
        if (max < 28 || min > 236) continue
        val saturation = (max - min).toDouble() / max
        val w = saturation * saturation
        r += pr * w
        g += pg * w
        b += pb * w
        weight += w
    }
    if (counted == 0 || weight / counted < MIN_MEAN_WEIGHT) return null
    return (0xFF shl 24) or
        ((r / weight).roundToInt().coerceIn(0, 255) shl 16) or
        ((g / weight).roundToInt().coerceIn(0, 255) shl 8) or
        (b / weight).roundToInt().coerceIn(0, 255)
}

private const val MIN_MEAN_WEIGHT = 0.02
private const val TINT_SAMPLE_PX = 24
private const val NO_TINT = 0

private val tints = LruCache<String, Int>(256)

/**
 * [coverTint] for a book's cover, sampled from a thumbnail the size of a
 * postage stamp. Null until it is known, when there is no artwork, when
 * the cover is grey, and always on e-ink, where a tint only dithers.
 */
@Composable
internal fun rememberCoverTint(book: Book): Color? {
    val artwork = book.effectiveCover
    val eInk = LocalEInk.current
    val context = LocalContext.current
    val tint by produceState(
        initialValue = artwork?.let { tints.get(it) }?.takeIf { it != NO_TINT }?.let { Color(it) },
        artwork,
        eInk,
    ) {
        if (artwork == null || eInk) {
            value = null
            return@produceState
        }
        val cached = tints.get(artwork)
        if (cached != null) {
            value = cached.takeIf { it != NO_TINT }?.let { Color(it) }
            return@produceState
        }
        val request = ImageRequest.Builder(context)
            .data(artwork)
            .browseCover(artwork, book.effectiveCoverUrl, book.browseServerId)
            .size(TINT_SAMPLE_PX)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            ?: return@produceState
        val argb = withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            coverTint(pixels)
        }
        tints.put(artwork, argb ?: NO_TINT)
        value = argb?.let { Color(it) }
    }
    return tint
}
