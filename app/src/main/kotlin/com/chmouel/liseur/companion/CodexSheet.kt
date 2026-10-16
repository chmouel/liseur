package com.chmouel.liseur.companion

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyBar
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import org.readium.r2.shared.publication.Locator
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexSheet(
    panel: CodexPanel,
    progress: CodexProgress,
    controller: CodexViewModel,
    onSource: (Passage<Locator>) -> Unit,
    // Held by the caller: the sheet leaves the screen during a peek, and
    // Return should land where the reader was in the list.
    namesList: LazyListState = rememberLazyListState(),
    detailList: LazyListState = rememberLazyListState(),
) {
    val tab = if (panel.relationships) 1 else 0
    val search = panel.search
    val detail = panel.detail
    LiseurModalBottomSheet(
        onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onBack = if (detail != null) controller::back else null,
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.codex_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = controller::close) { Text(stringResource(R.string.close)) }
            }
            CodexScanProgress(progress, controller)
            if (panel.placing) {
                BusyBar()
            } else if (panel.ceiling == null) {
                Text(stringResource(R.string.codex_unplaced))
            } else {
                Text(stringResource(R.string.codex_scope, panel.ceiling + 1), style = MaterialTheme.typography.bodySmall)
                if (detail != null) {
                    TextButton(onClick = controller::back) { Text(stringResource(R.string.back)) }
                    Text(detail.other?.let { stringResource(R.string.codex_pair, detail.name, it) } ?: detail.name,
                        style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f), state = detailList, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(detail.passages, key = { it.ref }) { passage ->
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSource(passage) }.padding(vertical = 12.dp)) {
                                val chapter = panel.titles.getOrNull(passage.range.hi)
                                    ?: stringResource(R.string.codex_chapter, passage.range.hi + 1)
                                Text(chapter, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    highlightedExcerpt(
                                        passage.text,
                                        listOfNotNull(detail.name, detail.other),
                                        SpanStyle(
                                            background = MaterialTheme.colorScheme.secondaryContainer,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider()
                        }
                        if (detail.loading) item { BusyBar() }
                        if (detail.failed) item {
                            Text(stringResource(R.string.codex_failed))
                            TextButton(onClick = controller::loadMore) { Text(stringResource(R.string.codex_retry)) }
                        }
                        if (!detail.loading && !detail.failed && detail.passages.isEmpty()) item { Text(stringResource(R.string.codex_no_passages)) }
                        if (detail.more && !detail.loading) item {
                            TextButton(onClick = controller::loadMore) { Text(stringResource(R.string.codex_more)) }
                        }
                    }
                } else {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = controller::showNames, text = { Text(stringResource(R.string.codex_names)) })
                        Tab(selected = tab == 1, onClick = controller::showGraph, text = { Text(stringResource(R.string.codex_relationships)) })
                    }
                    if (tab == 0) {
                        OutlinedTextField(value = search, onValueChange = controller::search, singleLine = true,
                            label = { Text(stringResource(R.string.codex_search)) }, modifier = Modifier.fillMaxWidth())
                        val entries = remember(panel.entries, search) {
                            val needle = PassageText.fold(search)
                            panel.entries.filter { PassageText.fold(it.name).contains(needle) }
                        }
                        LazyColumn(Modifier.weight(1f), state = namesList) {
                            if (entries.isEmpty()) item {
                                Text(stringResource(R.string.codex_empty), Modifier.padding(vertical = 16.dp))
                                // Only a finished scan with no names at all points at the script;
                                // a search matching nothing, or a scan just begun, does not.
                                if (panel.entries.isEmpty() && progress.stage == CodexStage.COMPLETE) {
                                    Text(stringResource(R.string.codex_scripts), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            items(entries, key = { it.name }) { entry ->
                                ListItem(
                                    headlineContent = { Text(entry.name) },
                                    supportingContent = { Text(stringResource(R.string.codex_entry, kindLabel(entry.kind), entry.occurrences)) },
                                    modifier = Modifier.clickable(role = Role.Button) { controller.detail(entry.name) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val graph = panel.graph
                            if (panel.graphFailed) item {
                                Text(stringResource(R.string.codex_failed))
                                TextButton(onClick = { controller.showGraph() }) { Text(stringResource(R.string.codex_retry)) }
                            } else if (graph == null) item { BusyBar() }
                            else if (graph.nodes.size < 2 || graph.edges.isEmpty()) item { Text(stringResource(R.string.codex_graph_empty)) }
                            else {
                                item { Text(stringResource(R.string.codex_graph_hint), style = MaterialTheme.typography.bodySmall) }
                                item { CodexMap(graph, controller::detail) }
                                items(graph.edges) { edge ->
                                    val a = graph.nodes[edge.a].name
                                    val b = graph.nodes[edge.b].name
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.codex_pair, a, b)) },
                                        supportingContent = { Text(stringResource(R.string.codex_shared, edge.weight)) },
                                        modifier = Modifier.clickable(role = Role.Button) { controller.detail(a, b) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where [terms] occur in [text], compared the way passage lookup compares
 * them ([PassageText.fold]), so O’Brien matches O'Brien and a decomposed
 * accent matches a composed one. Ranges index the original [text].
 */
internal fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
    val folded = StringBuilder(text.length)
    val starts = ArrayList<Int>(text.length)
    val ends = ArrayList<Int>(text.length)
    var space = true
    var i = 0
    while (i < text.length) {
        val next = i + Character.charCount(text.codePointAt(i))
        Normalizer.normalize(text.substring(i, next).lowercase(), Normalizer.Form.NFD).forEach { c ->
            val keep = when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> null
                c.isLetterOrDigit() -> c.also { space = false }
                !space -> ' '.also { space = true }
                else -> null
            }
            if (keep != null) {
                folded.append(keep)
                starts.add(i)
                ends.add(next)
            }
        }
        i = next
    }
    return terms.flatMap { term ->
        val needle = PassageText.fold(term)
        if (needle.isEmpty()) return@flatMap emptyList()
        buildList {
            var from = 0
            while (true) {
                val match = folded.indexOf(needle, from)
                if (match < 0) break
                var end = ends[match + needle.length - 1]
                // Combining accents after the last letter belong to it.
                while (end < text.length && Character.getType(text[end]) == Character.NON_SPACING_MARK.toInt()) end++
                add(starts[match] until end)
                from = match + needle.length
            }
        }
    }.sortedBy { it.first }
}

private fun highlightedExcerpt(text: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    val matches = highlightRanges(text, terms)
    return buildAnnotatedString {
        var cursor = 0
        for (match in matches) {
            if (match.first < cursor) continue
            append(text.substring(cursor, match.first))
            withStyle(style) { append(text.substring(match.first, match.last + 1)) }
            cursor = match.last + 1
        }
        append(text.substring(cursor))
    }
}

@Composable
private fun kindLabel(kind: CodexKind): String = stringResource(when (kind) {
    CodexKind.PERSON -> R.string.codex_person
    CodexKind.PLACE -> R.string.codex_place
    CodexKind.FACTION -> R.string.codex_faction
    CodexKind.UNKNOWN -> R.string.codex_other
})

@Composable
private fun CodexScanProgress(progress: CodexProgress, controller: CodexViewModel) {
    Column {
        Text(stringResource(when (progress.stage) {
            CodexStage.PREPARING -> R.string.codex_preparing
            CodexStage.SCANNING -> R.string.codex_scanning
            CodexStage.PAUSED -> R.string.codex_paused
            CodexStage.COMPLETE -> R.string.codex_complete
            CodexStage.FAILED -> R.string.codex_failed
        }), style = MaterialTheme.typography.labelLarge)
        if (progress.total > 0) {
            Text(stringResource(R.string.codex_progress, progress.completed, progress.total), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        } else if (progress.stage == CodexStage.PREPARING) BusyBar()
        when (progress.stage) {
            CodexStage.PREPARING, CodexStage.SCANNING -> TextButton(onClick = controller::pause) { Text(stringResource(R.string.codex_pause)) }
            CodexStage.PAUSED -> TextButton(onClick = controller::resume) { Text(stringResource(R.string.codex_resume)) }
            CodexStage.FAILED -> TextButton(onClick = controller::resume) { Text(stringResource(R.string.codex_retry)) }
            CodexStage.COMPLETE -> Unit
        }
    }
}

@Composable
private fun CodexMap(graph: CodexGraph, onPair: (String, String?) -> Unit) {
    val positions = remember(graph) { codexLayout(graph) }
    // By name, so a graph rebuilt as the scan goes on keeps a half-chosen pair.
    var selectedName by remember { mutableStateOf<String?>(null) }
    fun selected(): Int? = selectedName?.let { name -> graph.nodes.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
    val color = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    // E-paper has few greys and rounds a faint line to white: lines are a
    // mid grey, a chosen name's links full ink and the rest dashed, and
    // labels are cut out of the lines crossing them.
    val eInk = LocalEInk.current
    val paper = MaterialTheme.colorScheme.surface
    val paint = remember(textColor) { Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = textColor.toArgb(); textAlign = Paint.Align.CENTER } }
    val density = LocalDensity.current
    fun labels(size: Size): Map<Int, Rect> = with(density) {
        paint.textSize = 11.dp.toPx()
        codexLabels(
            centers = positions.map { Offset(it.first * size.width, it.second * size.height) },
            widths = graph.nodes.map { paint.measureText(it.name.take(22)) },
            textHeight = paint.fontSpacing,
            gap = 10.dp.toPx(),
            dot = 9.dp.toPx(),
            bounds = size,
            order = codexLabelOrder(graph, selected()),
            force = selected(),
        )
    }
    Canvas(Modifier.fillMaxWidth().height(320.dp).pointerInput(graph) {
        detectTapGestures { tap ->
            val canvas = Size(size.width.toFloat(), size.height.toFloat())
            val hit = labels(canvas).entries.firstOrNull { it.value.contains(tap) }?.key
                ?: positions.indices.minByOrNull { i ->
                    (Offset(positions[i].first * canvas.width, positions[i].second * canvas.height) - tap).getDistanceSquared()
                }?.takeIf { i -> (Offset(positions[i].first * canvas.width, positions[i].second * canvas.height) - tap).getDistance() <= 28.dp.toPx() }
            val previous = selected()
            if (hit != null && previous != null && hit != previous) {
                onPair(graph.nodes[previous].name, graph.nodes[hit].name)
                selectedName = null
            } else selectedName = hit.takeUnless { it == previous }?.let { graph.nodes[it].name }
        }
    }) {
        fun point(i: Int) = Offset(positions[i].first * size.width, positions[i].second * size.height)
        val focus = selected()
        graph.edges.forEach {
            val near = focus == null || it.a == focus || it.b == focus
            val alpha = when {
                !eInk -> if (near) 0.3f else 0.1f
                focus == null -> 0.5f
                near -> 1f
                else -> 0.3f
            }
            val dash = if (eInk && !near) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null
            drawLine(color.copy(alpha = alpha), point(it.a), point(it.b), (1f + 3f * it.weight / graph.maxWeight).dp.toPx(), pathEffect = dash)
        }
        val placed = labels(size)
        graph.nodes.forEachIndexed { i, _ -> drawCircle(color, if (focus == i) 9.dp.toPx() else 5.dp.toPx(), point(i)) }
        drawIntoCanvas { canvas ->
            placed.forEach { (i, box) ->
                val name = graph.nodes[i].name.take(22)
                val baseline = box.top - paint.fontMetrics.ascent
                if (eInk) {
                    val fill = paint.color
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 4.dp.toPx()
                    paint.color = paper.toArgb()
                    canvas.nativeCanvas.drawText(name, box.center.x, baseline, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = fill
                }
                canvas.nativeCanvas.drawText(name, box.center.x, baseline, paint)
            }
        }
    }
}

/** Labels in order of interest: the selected name, its connections by strength, then by frequency. */
internal fun codexLabelOrder(graph: CodexGraph, selected: Int?): List<Int> {
    if (selected == null) return graph.nodes.indices.toList()
    val neighbours = graph.edges.filter { it.a == selected || it.b == selected }
        .sortedByDescending { it.weight }
        .map { if (it.a == selected) it.b else it.a }
    return (listOf(selected) + neighbours + graph.nodes.indices).distinct()
}

/**
 * Where each label fits, below, above, right or left of its dot, inside
 * [bounds] and clear of earlier labels and other dots. A name with no room
 * stays a dot until selected; [force] is always labelled, over a dot if it
 * must.
 */
internal fun codexLabels(
    centers: List<Offset>,
    widths: List<Float>,
    textHeight: Float,
    gap: Float,
    dot: Float,
    bounds: Size,
    order: List<Int>,
    force: Int? = null,
): Map<Int, Rect> {
    val placed = LinkedHashMap<Int, Rect>()
    for (i in order) {
        val c = centers[i]
        val w = minOf(widths[i], bounds.width)
        fun box(left: Float, top: Float): Rect {
            val x = left.coerceIn(0f, bounds.width - w)
            val y = top.coerceIn(0f, maxOf(0f, bounds.height - textHeight))
            return Rect(x, y, x + w, y + textHeight)
        }
        val candidates = listOf(
            box(c.x - w / 2, c.y + gap),
            box(c.x - w / 2, c.y - gap - textHeight),
            box(c.x + gap, c.y - textHeight / 2),
            box(c.x - gap - w, c.y - textHeight / 2),
        )
        val clearOfLabels = candidates.filter { box -> placed.values.none { it.overlaps(box) } }
        val fits = clearOfLabels.firstOrNull { box -> centers.indices.none { j -> j != i && Rect(centers[j], dot).overlaps(box) } }
            ?: if (i == force) clearOfLabels.firstOrNull() ?: candidates.first() else null
        fits?.let { placed[i] = it }
    }
    return placed
}

/** Small deterministic spring layout; graph sizes are capped at 25 nodes. */
internal fun codexLayout(graph: CodexGraph): List<Pair<Float, Float>> {
    val n = graph.nodes.size
    if (n == 0) return emptyList()
    if (n == 1) return listOf(0.5f to 0.5f)
    val x = DoubleArray(n) { 0.5 + 0.35 * cos(2 * Math.PI * it / n) }
    val y = DoubleArray(n) { 0.5 + 0.35 * sin(2 * Math.PI * it / n) }
    val k = 0.8 / sqrt(n.toDouble())
    repeat(150) { iteration ->
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        for (a in 0 until n) for (b in a + 1 until n) {
            val vx = x[a] - x[b]
            val vy = y[a] - y[b]
            val distance = maxOf(0.001, sqrt(vx * vx + vy * vy))
            val force = k * k / distance
            dx[a] += vx / distance * force; dy[a] += vy / distance * force
            dx[b] -= vx / distance * force; dy[b] -= vy / distance * force
        }
        for (edge in graph.edges) {
            val a = edge.a; val b = edge.b
            val vx = x[a] - x[b]; val vy = y[a] - y[b]
            val distance = maxOf(0.001, sqrt(vx * vx + vy * vy))
            val force = distance * distance / k * 0.3
            dx[a] -= vx / distance * force; dy[a] -= vy / distance * force
            dx[b] += vx / distance * force; dy[b] += vy / distance * force
        }
        val temperature = 0.08 * (1.0 - iteration / 150.0)
        for (i in 0 until n) {
            dx[i] += (0.5 - x[i]) * 0.1; dy[i] += (0.5 - y[i]) * 0.1
            val distance = maxOf(0.001, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
            val step = minOf(temperature, distance)
            x[i] += dx[i] / distance * step; y[i] += dy[i] / distance * step
        }
    }
    // Unconnected names would stretch the frame and crush the connected ones into a corner;
    // frame the connected ones and let the others settle at the edges.
    val linked = graph.edges.flatMap { listOf(it.a, it.b) }.toSet().takeIf { it.size >= 2 } ?: (0 until n).toSet()
    val left = linked.minOf { x[it] }; val top = linked.minOf { y[it] }
    val width = maxOf(0.001, linked.maxOf { x[it] } - left); val height = maxOf(0.001, linked.maxOf { y[it] } - top)
    for (i in 0 until n) {
        x[i] = (x[i] - left) / width * 0.72 + 0.14
        y[i] = (y[i] - top) / height * 0.76 + 0.08
    }
    // The spring layout can still stack dots; push close pairs apart so each stays tappable.
    for (round in 0 until 100) {
        var moved = false
        for (a in 0 until n) for (b in a + 1 until n) {
            var vx = x[a] - x[b]
            var vy = y[a] - y[b]
            var distance = sqrt(vx * vx + vy * vy)
            if (distance >= MIN_NODE_GAP) continue
            if (distance < 1e-6) { vx = cos(a + b.toDouble()); vy = sin(a + b.toDouble()); distance = 1.0 }
            val push = (MIN_NODE_GAP - distance) / 2
            x[a] += vx / distance * push; y[a] += vy / distance * push
            x[b] -= vx / distance * push; y[b] -= vy / distance * push
            moved = true
        }
        for (i in 0 until n) { x[i] = x[i].coerceIn(0.06, 0.94); y[i] = y[i].coerceIn(0.05, 0.95) }
        if (!moved) break
    }
    return (0 until n).map { x[it].toFloat() to y[it].toFloat() }
}

/** Distance between dot centres, as a fraction of the map, that keeps each dot's tap target its own. */
internal const val MIN_NODE_GAP = 0.09
