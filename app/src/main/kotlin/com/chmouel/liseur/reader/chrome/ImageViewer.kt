package com.chmouel.liseur.reader.chrome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.LocalEInk

/** Enough of the scrim to keep a caption legible over a pale picture. */
private val CAPTION_BACKING = Color.Black.copy(alpha = 0.66f)

/**
 * Somewhere to keep a gesture that is being counted and not drawn.
 *
 * A plain box rather than a `MutableState`, on purpose: reading a state
 * object from the gesture callback and writing it back would recompose
 * on every pointer change, which is the repaint the whole arrangement
 * exists to avoid.
 */
private class PendingGesture {
    var value: PendingTransform? = null
}

/**
 * The largest picture decoded at its own size rather than the screen's.
 *
 * A decoded bitmap costs four bytes a pixel whatever the file weighs, so
 * eight megapixels is thirty-two megabytes held for as long as the viewer
 * is open. That is affordable, and it is roughly where book plates stop:
 * a scan far bigger than this is a page of an atlas, and it goes back to
 * decoding for the screen rather than risking the process.
 */
private const val MAX_DECODE_PIXELS = 8_000_000L

/** A picture the reader asked to see, with the book's own caption for it. */
data class ViewedImage(
    val bytes: ByteArray,
    val alt: String?,
    val caption: String?,
    val width: Int,
    val height: Int,
) {
    /**
     * The line printed under the picture.
     *
     * The book's own caption where it wrote one, and the alternative text
     * otherwise — which is a description rather than a caption, but a
     * description of the thing on screen is still better than a blank
     * strip, and a great many books carry nothing else.
     */
    val subtitle: String? get() = caption ?: alt

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ViewedImage && bytes === other.bytes && alt == other.alt &&
                    caption == other.caption
                )

    override fun hashCode(): Int =
        System.identityHashCode(bytes) * 31 + (subtitle?.hashCode() ?: 0)
}

/**
 * A picture from the book, on the app's own page rather than the book's.
 *
 * The alternative was zooming the web view in place, and it does not
 * work: an enlarged image fights the paginated column layout it sits in,
 * there is nothing to dismiss, and the reader is left pinching their way
 * back to exactly 1.0 before the page turns properly again. A full-screen
 * overlay has none of those problems and is the only place `BackHandler`
 * means anything. See `docs/adr/0022-pinch-on-the-page.md`.
 *
 * Drawn over everything on a black scrim, in Material colours rather than
 * in the reading theme: this is not the page any more, and a picture is
 * judged against black in every other viewer the reader owns.
 *
 * Except on electronic paper, where it is drawn on [theme]'s own paper.
 * Driving every pixel to black and back again is the slowest and
 * ghostiest thing such a panel does, and it would happen twice for every
 * picture; on paper the picture is a page-sized change rather than a
 * full inversion. The gesture is held back for the same reason and
 * applied once on the lift.
 */
@Composable
fun ImageViewer(image: ViewedImage, theme: ReaderTheme, onDismiss: () -> Unit) {
    var scale by remember(image) { mutableFloatStateOf(ImageZoom.MIN_SCALE) }
    var offsetX by remember(image) { mutableFloatStateOf(0f) }
    var offsetY by remember(image) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // The picture's own size, not the composable's. The image is drawn to
    // fit inside a box that fills the screen, so the box says nothing
    // about how far the picture reaches: panning by the box would let the
    // reader drag the letterbox into view and lose the picture off the
    // other edge.
    var natural by remember(image) { mutableStateOf(Size.Unspecified) }
    // Counted apart from `offsetY`, which is clamped to nothing at fit
    // because a fitted picture has nowhere to pan to. Adding the pan to
    // an offset that is about to be zeroed again measures one frame of
    // travel rather than a drag, and one frame is never far enough.
    var dragDown by remember(image) { mutableFloatStateOf(0f) }
    val dismissTravel = with(LocalDensity.current) { ImageZoom.DISMISS_TRAVEL_DP.dp.toPx() }
    val context = LocalContext.current
    val viewerTitle = image.alt ?: stringResource(R.string.reader_image_viewer)
    val onPaper = LocalEInk.current
    // The scrim, and the one colour everything drawn over it is in.
    val backdrop = if (onPaper) theme.background else Color.Black
    val ink = if (onPaper) theme.foreground else Color.White
    // Deliberately not a state object: the whole point of accumulating a
    // gesture is that no frame of it recomposes anything.
    val pending = remember(image) { PendingGesture() }

    fun hold() {
        val (w, h) = ImageZoom.fitted(
            contentW = natural.takeIf { it.isSpecified }?.width ?: 0f,
            contentH = natural.takeIf { it.isSpecified }?.height ?: 0f,
            viewW = viewport.width.toFloat(),
            viewH = viewport.height.toFloat(),
        )
        offsetX = ImageZoom.clampPan(offsetX, viewport.width.toFloat(), w * scale)
        offsetY = ImageZoom.clampPan(offsetY, viewport.height.toFloat(), h * scale)
    }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            // Modal in the way that matters to somebody who cannot see
            // it: without this the reader's own chrome stays reachable
            // behind the picture, so a swipe lands on a control that is
            // not on screen. Nothing under the viewer is addressable
            // while it is up, which is already true for touch.
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
                paneTitle = viewerTitle
            }
            .onSizeChanged { viewport = it }
            .pointerInput(image) {
                detectTapGestures(
                    // A tap while zoomed in is how a reader steadies the
                    // picture, so only a tap at fit is a tap meaning
                    // "done with this".
                    onTap = { if (ImageZoom.atFit(scale)) onDismiss() },
                    onDoubleTap = { point ->
                        if (ImageZoom.atFit(scale)) {
                            scale = ImageZoom.DOUBLE_TAP_SCALE
                            // Zoom towards what was tapped, not towards
                            // the middle: the reader tapped the corner of
                            // the map they want to read.
                            offsetX = (viewport.width / 2f - point.x) * (scale - 1f)
                            offsetY = (viewport.height / 2f - point.y) * (scale - 1f)
                        } else {
                            scale = ImageZoom.MIN_SCALE
                            offsetX = 0f
                            offsetY = 0f
                        }
                        hold()
                    },
                )
            }
            .pointerInput(image, onPaper) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // On paper the frame is counted and nothing is
                    // written: `scale` and the translations below are
                    // what the screen is drawn from, and each of them
                    // costs a full repaint.
                    if (onPaper) {
                        val soFar = pending.value ?: PendingTransform(scale, offsetX, offsetY)
                        pending.value = soFar.fold(zoom, pan.x, pan.y)
                        return@detectTransformGestures
                    }
                    val wasAtFit = ImageZoom.atFit(scale)
                    scale = ImageZoom.clampScale(scale * zoom)
                    offsetX += pan.x
                    offsetY += pan.y
                    // Dragging a picture that already fits has nowhere to
                    // go, so it means put it away. Downwards only: it is
                    // the direction every other viewer uses, and it does
                    // not collide with the sideways pan of a wide plate.
                    // Travel back up takes it off again, so a hand that
                    // wanders down and comes back has not asked.
                    if (wasAtFit && ImageZoom.atFit(scale)) {
                        dragDown = ImageZoom.dragTravel(dragDown, pan.y)
                        if (dragDown > dismissTravel) onDismiss()
                    } else {
                        dragDown = 0f
                    }
                    hold()
                }
            }
            // `detectTransformGestures` has no gesture-end callback, and
            // the travel above needs one: distance covered by two
            // separate drags is not a drag. It is also where a gesture
            // counted rather than drawn is finally applied, in one step.
            // Never consumed, so the detector above still sees
            // everything; the down is watched on the initial pass so
            // that nothing beats us to it, and the release on the final
            // one, so that the last frame of the gesture has already
            // been folded in before it is committed.
            .pointerInput(image, onPaper) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                    dragDown = 0f
                    pending.value?.let { gesture ->
                        pending.value = null
                        if (gesture.dismisses(dismissTravel)) {
                            onDismiss()
                        } else {
                            scale = gesture.scale
                            offsetX = gesture.offsetX
                            offsetY = gesture.offsetY
                            hold()
                        }
                    }
                }
            },
    ) {
        // Decoded at the picture's own size rather than at the size of the
        // screen, so that zooming in reveals the plate instead of
        // magnifying a screen-sized copy of it. A book can name any size
        // it likes, though, and a bitmap costs four bytes a pixel however
        // little the file weighs, so past a budget it goes back to
        // decoding for the screen: blurred at six times is a worse
        // picture, and a dead process is no picture at all.
        val model = remember(image) {
            ImageRequest.Builder(context)
                .data(image.bytes)
                .apply {
                    val pixels = image.width.toLong() * image.height.toLong()
                    if (pixels in 1..MAX_DECODE_PIXELS) size(CoilSize.ORIGINAL)
                }
                .build()
        }
        AsyncImage(
            model = model,
            contentDescription = image.alt,
            contentScale = ContentScale.Fit,
            onSuccess = { natural = it.painter.intrinsicSize },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                // Paper under the ink. A great many book illustrations are
                // black line art on a transparent background — Standard
                // Ebooks marks them `se:image.color-depth.black-on-transparent`
                // — and on the scrim those are simply invisible. Drawn to
                // the picture's own fitted rectangle rather than to the
                // whole screen, so a photograph covers it completely and
                // never shows a white border. Not needed on an e-paper
                // panel, where the viewer is already on the reading
                // theme's paper and the line art lands on it as it does
                // on the page.
                .drawBehind {
                    if (onPaper || !natural.isSpecified) return@drawBehind
                    val (w, h) = ImageZoom.fitted(
                        contentW = natural.width,
                        contentH = natural.height,
                        viewW = size.width,
                        viewH = size.height,
                    )
                    if (w <= 0f || h <= 0f) return@drawBehind
                    drawRect(
                        color = Color.White,
                        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h),
                    )
                },
        )

        image.subtitle?.let { caption ->
            Text(
                text = caption,
                color = ink,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    // The caption sits over whatever the picture leaves
                    // there, which after the paper behind a line drawing
                    // can be white. That backing goes with the white
                    // rectangle it was there for: on an e-paper panel the
                    // caption is the theme's own ink on the theme's own
                    // paper, and a pill around it is one more edge for
                    // the panel to ghost.
                    .then(
                        if (onPaper) {
                            Modifier
                        } else {
                            Modifier
                                .background(CAPTION_BACKING, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        },
                    ),
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
                tint = ink,
            )
        }
    }
}
