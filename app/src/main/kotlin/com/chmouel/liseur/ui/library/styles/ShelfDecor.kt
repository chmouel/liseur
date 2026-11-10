package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.ui.browseCover
import kotlin.math.roundToInt

/*
 * The stone bookcase drawn behind the Shelves style. The images come from
 * hack/shelf-textures in grayscale and are multiplied by theme colours here,
 * so the wall follows light, dark and dynamic colour. The plants keep their
 * own colours. E-paper gets none of it: callers check LocalEInk and keep the
 * flat plank.
 */

private val WallTile = 128.dp
private val LedgeTileWidth = 32.dp
private val LedgeStripHeight = 38.dp
private val BracketWidth = 18.dp

/** Full height of [StoneLedge], brackets included. */
internal val StoneLedgeHeight = 52.dp

/** How far below the ledge top a book's foot rests on the stone. */
internal val StoneLedgeFoot = 7.dp

/** Room left at each end of a stone shelf for a small pot. */
internal val StoneShelfGutter = 30.dp

private val PotWidth = 22.dp
private val HangingWidth = 38.dp

/** Where the hanging plant's hook sits, under the shelf's front lip. */
private val HangingTop = LedgeStripHeight - 2.dp

private val Paper = Color(0xFFF6F3EB)
private val PageEdge = Color(0xFFBDB8AD)

@Immutable
internal class ShelfArt(
    val wall: ImageBitmap,
    val ledge: ImageBitmap,
    val bracket: ImageBitmap,
    val rectFrame: ImageBitmap,
    val ovalFrame: ImageBitmap,
    val succulent: ImageBitmap,
    val fern: ImageBitmap,
    val flowers: ImageBitmap,
    val trailing: ImageBitmap,
    val wallTint: ColorFilter,
    val stoneTint: ColorFilter,
    val giltTint: ColorFilter,
    /** Laid over the bricks to soften them behind text. */
    val wash: Color,
    /** Behind the controls, so the buttons read on the bricks. */
    val panel: Color,
    val panelEdge: Color,
)

@Composable
internal fun rememberShelfArt(): ShelfArt {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val wall = ImageBitmap.imageResource(R.drawable.shelf_wall)
    val ledge = ImageBitmap.imageResource(R.drawable.shelf_ledge)
    val bracket = ImageBitmap.imageResource(R.drawable.shelf_ledge_end)
    val rect = ImageBitmap.imageResource(R.drawable.shelf_frame_rect)
    val oval = ImageBitmap.imageResource(R.drawable.shelf_frame_oval)
    val succulent = ImageBitmap.imageResource(R.drawable.shelf_plant_succulent)
    val fern = ImageBitmap.imageResource(R.drawable.shelf_plant_fern)
    val flowers = ImageBitmap.imageResource(R.drawable.shelf_plant_flowers)
    val trailing = ImageBitmap.imageResource(R.drawable.shelf_plant_trailing)
    val wallColor = if (dark) scheme.surfaceContainerHighest else scheme.surfaceDim
    val stoneColor = if (dark) scheme.onSurfaceVariant else scheme.surfaceContainerLowest
    val giltColor = if (dark) Color(0xFFC2A458) else Color(0xFFD4B460)
    val wash = scheme.surface.copy(alpha = if (dark) 0.12f else 0.5f)
    val panel = scheme.surface.copy(alpha = 0.85f)
    val panelEdge = scheme.outlineVariant.copy(alpha = 0.6f)
    return remember(
        wall, ledge, bracket, rect, oval, succulent, fern, flowers, trailing,
        wallColor, stoneColor, giltColor, wash, panel, panelEdge,
    ) {
        ShelfArt(
            wall = wall,
            ledge = ledge,
            bracket = bracket,
            rectFrame = rect,
            ovalFrame = oval,
            succulent = succulent,
            fern = fern,
            flowers = flowers,
            trailing = trailing,
            wallTint = ColorFilter.tint(wallColor, BlendMode.Modulate),
            stoneTint = ColorFilter.tint(stoneColor, BlendMode.Modulate),
            giltTint = ColorFilter.tint(giltColor, BlendMode.Modulate),
            wash = wash,
            panel = panel,
            panelEdge = panelEdge,
        )
    }
}

/** Tiles [image] over the given area, edge to edge without seams. */
private fun DrawScope.tile(
    image: ImageBitmap,
    tileWidth: Float,
    tileHeight: Float,
    area: Size,
    colorFilter: ColorFilter,
) = clipRect(right = area.width, bottom = area.height) {
    var row = 0
    while (row * tileHeight < area.height) {
        val top = (row * tileHeight).roundToInt()
        val bottom = ((row + 1) * tileHeight).roundToInt()
        var col = 0
        while (col * tileWidth < area.width) {
            val left = (col * tileWidth).roundToInt()
            val right = ((col + 1) * tileWidth).roundToInt()
            drawImage(
                image = image,
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(right - left, bottom - top),
                colorFilter = colorFilter,
            )
            col++
        }
        row++
    }
}

internal fun DrawScope.drawShelfWall(art: ShelfArt) {
    val tile = WallTile.toPx()
    tile(art.wall, tile, tile, size, art.wallTint)
    drawRect(art.wash)
}

/**
 * A stone shelf with a dentil band under it and a bracket at each end.
 * [hanging] hangs a pothos under that end; it spills past the bottom of
 * the ledge, behind whatever the next row draws.
 */
@Composable
internal fun StoneLedge(art: ShelfArt, modifier: Modifier = Modifier, hanging: ShelfEnd? = null) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(StoneLedgeHeight),
    ) {
        tile(
            image = art.ledge,
            tileWidth = LedgeTileWidth.toPx(),
            tileHeight = LedgeStripHeight.toPx(),
            area = Size(size.width, LedgeStripHeight.toPx()),
            colorFilter = art.stoneTint,
        )
        val bracketSize = IntSize(BracketWidth.roundToPx(), size.height.roundToInt())
        drawImage(art.bracket, dstSize = bracketSize, colorFilter = art.stoneTint)
        scale(scaleX = -1f, scaleY = 1f) {
            drawImage(art.bracket, dstSize = bracketSize, colorFilter = art.stoneTint)
        }
        if (hanging != null) {
            val width = HangingWidth.toPx()
            val height = width * art.trailing.height / art.trailing.width
            val centre = (StoneShelfGutter / 2).toPx()
            val left = if (hanging == ShelfEnd.START) centre - width / 2 else size.width - centre - width / 2
            drawImage(
                image = art.trailing,
                dstOffset = IntOffset(left.roundToInt(), HangingTop.roundToPx()),
                dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            )
        }
    }
}

/** A small succulent in a clay pot, stood at one end of a shelf. */
@Composable
internal fun ShelfPot(art: ShelfArt, modifier: Modifier = Modifier) {
    Image(
        bitmap = art.succulent,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(width = PotWidth, height = PotWidth * art.succulent.height / art.succulent.width),
    )
}

/** A potted plant standing in an empty place on a shelf. */
@Composable
internal fun ShelfPlant(kind: PlantKind, art: ShelfArt, modifier: Modifier = Modifier) {
    val bitmap = when (kind) {
        PlantKind.FERN -> art.fern
        PlantKind.FLOWERS -> art.flowers
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height),
    )
}

/** The top edge of the pages, seen from slightly above the shelf. */
@Composable
internal fun PageBlock(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(5.dp),
    ) {
        drawRect(PageEdge)
        val inset = 2.dp.toPx()
        drawRect(
            color = Paper,
            topLeft = Offset(inset, 1.dp.toPx()),
            size = Size(size.width - inset * 2, size.height - 1.dp.toPx()),
        )
        for (fraction in listOf(0.45f, 0.75f)) {
            val y = size.height * fraction
            drawLine(
                color = Color.Black.copy(alpha = 0.12f),
                start = Offset(inset, y),
                end = Offset(size.width - inset, y),
                strokeWidth = 1f,
            )
        }
    }
}

/** A bookmark ribbon with a check mark, hanging over a finished book. */
@Composable
internal fun FinishedRibbon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 14.dp, height = 20.dp)) {
        val notch = size.height * 0.22f
        val ribbon = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2, size.height - notch)
            lineTo(0f, size.height)
            close()
        }
        drawPath(ribbon, Color.White)
        drawPath(ribbon, Color.Black, style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round))
        val check = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.36f)
            lineTo(size.width * 0.44f, size.height * 0.56f)
            lineTo(size.width * 0.8f, size.height * 0.16f)
        }
        drawPath(
            check,
            Color.Black,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private val OvalShape = GenericShape { size, _ -> addOval(Rect(Offset.Zero, size)) }

/**
 * A finished book's cover hung on the wall as a painting. Purely
 * decorative: it takes no clicks and says nothing to accessibility.
 */
@Composable
internal fun ShelfFrame(book: Book, oval: Boolean, art: ShelfArt, modifier: Modifier = Modifier) {
    val shape = if (oval) OvalShape else RectangleShape
    // Share of the frame image left clear in the middle, a little smaller
    // than the drawn hole so the picture tucks under the moulding.
    val (holeWidth, holeHeight) = if (oval) 0.84f to 0.84f else 0.76f to 0.81f
    val artwork = book.coverPath ?: book.coverUrl
    val context = LocalContext.current
    val request = remember(artwork, context, book.browseServerId) {
        artwork?.let {
            ImageRequest.Builder(context)
                .data(it)
                .browseCover(it, book.coverUrl, book.browseServerId)
                .build()
        }
    }
    Box(
        modifier
            .aspectRatio(300f / 380f)
            .shadow(elevation = 8.dp, shape = shape, clip = false),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(holeWidth)
                .fillMaxHeight(holeHeight)
                .clip(shape)
                .background(Paper),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (oval) 0.dp else 6.dp),
            )
        }
        Image(
            bitmap = if (oval) art.ovalFrame else art.rectFrame,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = art.giltTint,
            modifier = Modifier.matchParentSize(),
        )
    }
}

internal enum class ShelfEnd { START, END }

internal enum class PlantKind { FERN, FLOWERS }

internal sealed interface SlotDecor {
    data class Frame(val book: Book, val oval: Boolean) : SlotDecor
    data class Plant(val kind: PlantKind) : SlotDecor
}

/** What decorates one shelf: its empty places and its two ends. */
internal data class PlankDecor(
    val slots: List<SlotDecor>,
    val pot: ShelfEnd?,
    val hanging: ShelfEnd?,
)

/**
 * Decides a plank's decoration from its key alone, so nothing moves while
 * scrolling. Empty places alternate between plants and framed covers of
 * finished books not already on the plank, no cover hung twice; with no
 * covers left they all get plants. About a third of the shelves get a pot
 * at one end and a third a hanging plant, never at the same end.
 */
internal fun plankDecor(plankKey: String, onPlank: Set<String>, finished: List<Book>, empty: Int): PlankDecor {
    // Neighbouring planks differ only by a trailing index, so spread the
    // hash before reading choices out of it.
    val seed = (plankKey.hashCode() * -0x61c88647) ushr 8
    val candidates = finished.filter { it.url !in onPlank }
    var nextCover = 0
    val slots = List(maxOf(empty, 0)) { slot ->
        val turn = seed + slot
        if (turn % 2 == 0 && nextCover < candidates.size) {
            val book = candidates[(seed + nextCover++) % candidates.size]
            SlotDecor.Frame(book, oval = (turn / 2) % 2 == 1)
        } else {
            SlotDecor.Plant(if ((seed / 2 + slot) % 2 == 0) PlantKind.FERN else PlantKind.FLOWERS)
        }
    }
    val pot = when ((seed / 5) % 3) {
        0 -> ShelfEnd.START
        1 -> ShelfEnd.END
        else -> null
    }
    val hanging = when {
        (seed / 7) % 3 != 0 -> null
        pot == ShelfEnd.END -> ShelfEnd.START
        else -> ShelfEnd.END
    }
    return PlankDecor(slots, pot, hanging)
}
