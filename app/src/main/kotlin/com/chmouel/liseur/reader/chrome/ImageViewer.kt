package com.chmouel.liseur.reader.chrome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chmouel.liseur.R

/** A picture the reader asked to see, with the book's own caption for it. */
data class ViewedImage(val bytes: ByteArray, val alt: String?) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ViewedImage && bytes === other.bytes && alt == other.alt)

    override fun hashCode(): Int = System.identityHashCode(bytes) * 31 + (alt?.hashCode() ?: 0)
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
 */
@Composable
fun ImageViewer(image: ViewedImage, onDismiss: () -> Unit) {
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
    val dismissTravel = with(LocalDensity.current) { ImageZoom.DISMISS_TRAVEL_DP.dp.toPx() }

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
            .background(Color.Black)
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
            .pointerInput(image) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val wasAtFit = ImageZoom.atFit(scale)
                    scale = ImageZoom.clampScale(scale * zoom)
                    offsetX += pan.x
                    offsetY += pan.y
                    // Dragging a picture that already fits has nowhere to
                    // go, so it means put it away. Downwards only: it is
                    // the direction every other viewer uses, and it does
                    // not collide with the sideways pan of a wide plate.
                    if (wasAtFit && ImageZoom.atFit(scale) && offsetY > dismissTravel) {
                        onDismiss()
                    }
                    hold()
                }
            },
    ) {
        AsyncImage(
            model = image.bytes,
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
                },
        )

        image.alt?.let { caption ->
            Text(
                text = caption,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
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
                tint = Color.White,
            )
        }
    }
}
