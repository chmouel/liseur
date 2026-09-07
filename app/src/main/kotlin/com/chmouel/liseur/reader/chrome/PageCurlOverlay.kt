package com.chmouel.liseur.reader.chrome

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A page being curled off the book by a finger, and finished or put
 * back when the finger lifts.
 *
 * The book underneath has already turned: `PageTurner` jumps the
 * navigator and hands the snapshot of the departing page here, the way
 * it does for the lifted turn. What differs is that the snapshot goes
 * where the finger says rather than where a tween does, and that it
 * can be pulled back flat, at which point the navigator is sent back
 * to where it was.
 *
 * All of this runs on the main thread from the pointer loop and the
 * composition; nothing here is shared with another thread.
 */
@Stable
class PageCurlState(private val scope: CoroutineScope) {

    var page by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Where the finger took hold, down the page. */
    var grabY by mutableFloatStateOf(0f)
        private set

    var tilt by mutableFloatStateOf(0f)
        private set

    var movesLeft by mutableStateOf(true)
        private set

    var paper by mutableIntStateOf(0)
        private set

    /** How far in the leading edge has come, in pixels. */
    var travel by mutableFloatStateOf(0f)
        private set

    /**
     * Held for the first frames after the navigator has jumped, with the
     * snapshot drawn flat, so that a frame in which the web view is
     * still painting the new page does not show a strip of the old one
     * where the curl has already begun.
     */
    private var settled by mutableStateOf(false)
    private var running: Job? = null

    val isRunning: Boolean get() = page != null

    /** Whether the finger still has the page. */
    var held = false
        private set

    fun begin(bitmap: ImageBitmap, grabY: Float, movesLeft: Boolean, paper: Int) {
        running?.cancel()
        this.grabY = grabY
        this.movesLeft = movesLeft
        this.paper = paper
        tilt = 0f
        travel = 0f
        settled = false
        held = true
        page = bitmap
        running = scope.launch {
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            settled = true
        }
    }

    /** The finger has moved: the edge is [travel] pixels in, and the finger [dy] down. */
    fun follow(travel: Float, dy: Float, height: Float) {
        if (!held) return
        tilt = PageCurl.tilt(grabY, dy, height)
        this.travel = travel.coerceAtLeast(0f)
    }

    /** The finger has let go and the page is to leave; [onDone] once it has. */
    fun finish(width: Float, height: Float, radius: Float, velocity: Float, onDone: () -> Unit) {
        if (!held) return
        held = false
        val clear = PageCurl.travelToClear(width, height, grabY, tilt, radius)
        settle(to = clear, velocity = velocity) {
            page = null
            onDone()
        }
    }

    /** The finger has let go and the page is to lie back down; [onFlat] once it has. */
    fun restore(velocity: Float, onFlat: () -> Unit) {
        if (!held) return
        held = false
        settle(to = 0f, velocity = velocity, then = onFlat)
    }

    /** The book has gone back to where it was; nothing more to draw. */
    fun drop() {
        held = false
        running?.cancel()
        page = null
    }

    private fun settle(to: Float, velocity: Float, then: () -> Unit) {
        running?.cancel()
        running = scope.launch {
            settled = true
            Animatable(travel).animateTo(
                targetValue = to,
                animationSpec = spring(stiffness = SETTLE_STIFFNESS),
                initialVelocity = velocity,
            ) {
                travel = value
            }
            then()
        }
    }

    /** The travel to draw this frame: none until the frames after the jump have passed. */
    internal fun drawnTravel(): Float = if (settled) travel else 0f

    private companion object {
        const val SETTLE_STIFFNESS = 600f
        const val SETTLE_FRAMES = 2
    }
}

/**
 * Draws the curled snapshot over the live navigator: the fold's shadow
 * on the page underneath, the ink side of the sheet bent over the roll,
 * and paper over the part that has turned face down.
 */
@Composable
fun PageCurlOverlay(state: PageCurlState, modifier: Modifier = Modifier) {
    val page = state.page ?: return
    val bitmap = page.asAndroidBitmap()
    val mesh = remember { PageCurl.Mesh(PageCurl.COLUMNS, PageCurl.ROWS) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val shadowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val shadow = remember { Path() }
    // The shadow's gradient runs along the fold's normal, which turns
    // and stretches every frame while its colours do not. Built once and
    // moved with a matrix, so that holding a curl still allocates
    // nothing on the drawing path.
    val shadowShader = remember {
        android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    val shadowMatrix = remember { Matrix() }
    // Vertex colours already carry the paper tint, so the tile is plain.
    val paperTile = remember {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.WHITE) }
    }
    Canvas(modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val radius = PageCurl.RADIUS_DP.dp.toPx()
        PageCurl.mesh(
            width = width,
            height = height,
            travel = state.drawnTravel(),
            grabY = state.grabY,
            tilt = state.tilt,
            movesLeft = state.movesLeft,
            radius = radius,
            paper = state.paper,
            out = mesh,
        )
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (mesh.curled && mesh.lift > 0f) {
                drawFoldShadow(
                    native, mesh, width, height, mesh.radius,
                    shadow, shadowPaint, shadowShader, shadowMatrix,
                )
            }
            native.drawBitmapMesh(bitmap, mesh.cols, mesh.rows, mesh.vertices, 0, mesh.front, 0, paint)
            if (mesh.curled) {
                native.drawBitmapMesh(bitmap, mesh.cols, mesh.rows, mesh.vertices, 0, mesh.back, 0, paint)
                native.drawBitmapMesh(paperTile, mesh.cols, mesh.rows, mesh.vertices, 0, mesh.paper, 0, paint)
                native.drawBitmapMesh(paperTile, mesh.cols, mesh.rows, mesh.vertices, 0, mesh.highlight, 0, paint)
            }
        }
    }
}

/**
 * A soft band of shadow on the page being revealed, along the roll's
 * silhouette, fading away from it. Drawn as a strip the width of the
 * roll's reach, tinted by how upright the roll is.
 */
private fun drawFoldShadow(
    canvas: android.graphics.Canvas,
    mesh: PageCurl.Mesh,
    width: Float,
    height: Float,
    radius: Float,
    path: Path,
    paint: Paint,
    shader: android.graphics.LinearGradient,
    matrix: Matrix,
) {
    val reach = mesh.silhouette + radius * SHADOW_REACH
    if (reach <= 0f) return
    val diag = width + height
    // The strip lies along the fold line (perpendicular to the normal),
    // from the silhouette outwards along the normal by the reach.
    val nx = mesh.normalX
    val ny = mesh.normalY
    val tx = -ny
    val ty = nx
    val sx = mesh.foldX + nx * mesh.silhouette
    val sy = mesh.foldY + ny * mesh.silhouette
    path.rewind()
    path.moveTo(sx + tx * diag, sy + ty * diag)
    path.lineTo(sx - tx * diag, sy - ty * diag)
    path.lineTo(sx - tx * diag + nx * reach, sy - ty * diag + ny * reach)
    path.lineTo(sx + tx * diag + nx * reach, sy + ty * diag + ny * reach)
    path.close()
    // The unit gradient runs (0,0) to (1,0); turn it onto the normal,
    // stretch it to the reach, and put its start on the silhouette.
    matrix.setSinCos(ny, nx)
    matrix.preScale(reach, reach)
    matrix.postTranslate(sx, sy)
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    // How dark the band is rides on the paint, since the shader's
    // colours are fixed: the roll standing further up casts more.
    paint.alpha = ((SHADOW_ALPHA * mesh.lift).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    canvas.drawPath(path, paint)
}

private const val SHADOW_ALPHA = 0.28f
private const val SHADOW_REACH = 0.6f
