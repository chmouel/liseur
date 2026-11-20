package com.chmouel.liseur.ui.settings

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.SupportReading
import com.chmouel.liseur.data.settings.CardStamp
import com.chmouel.liseur.reader.chrome.ReaderHaptic
import com.chmouel.liseur.reader.chrome.performReaderHaptic
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.stats.readingDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class SupportLedgerRow(
    val bookUrl: String,
    val title: String,
    val dateMillis: Long,
    val durationMs: Long,
)

internal data class SupportLedger(
    val rows: List<SupportLedgerRow>,
    val bookCount: Int,
    val totalDurationMs: Long,
)

internal fun buildSupportLedger(
    books: List<Book>,
    reading: List<SupportReading>,
    maxRows: Int = 4,
): SupportLedger {
    val visible = books.filterNot { it.hidden || it.archived }
    val byUrl = visible.associateBy { it.url }
    val readRows = reading.mapNotNull { entry ->
        val book = byUrl[entry.bookUrl] ?: return@mapNotNull null
        SupportLedgerRow(
            bookUrl = book.url,
            title = book.title,
            dateMillis = entry.lastReadAt,
            durationMs = entry.durationMs,
        )
    }
    val readUrls = readRows.mapTo(HashSet()) { it.bookUrl }
    val shelfFallback = visible
        .asSequence()
        .filter { it.url !in readUrls }
        .filter {
            it.downloadState == DownloadState.DOWNLOADED ||
                it.lastOpenedAt != null ||
                it.finishedAt != null
        }
        .sortedWith(
            compareByDescending<Book> { it.lastOpenedAt ?: it.finishedAt ?: it.addedAt }
                .thenBy { it.url },
        )
        .map { book ->
            SupportLedgerRow(
                bookUrl = book.url,
                title = book.title,
                dateMillis = book.lastOpenedAt ?: book.finishedAt ?: book.addedAt,
                durationMs = 0L,
            )
        }
        .toList()
    val combined = readRows + shelfFallback
    return SupportLedger(
        rows = combined.take(maxRows),
        bookCount = combined.size,
        totalDurationMs = readRows.sumOf { it.durationMs },
    )
}

private data class PocketPalette(
    val cardBg: Color,
    val cardBorder: Color,
    val ledgerLine: Color,
    val columnRule: Color,
    val pocketBack: Color,
    val pocketFront: Color,
    val pocketBorder: Color,
    val pocketStitch: Color,
    val dateInk: Color,
    val titleInk: Color,
    val mutedInk: Color,
    val stampInk: Color,
    val buttonBg: Color,
    val buttonFg: Color,
)

private fun pocketPalette(colors: ColorScheme, eInk: Boolean): PocketPalette {
    if (eInk) {
        return PocketPalette(
            cardBg = colors.surface,
            cardBorder = colors.outline,
            ledgerLine = colors.outlineVariant,
            columnRule = colors.outline,
            pocketBack = colors.surfaceContainerHighest,
            pocketFront = colors.surfaceContainerLow,
            pocketBorder = colors.outline,
            pocketStitch = colors.outlineVariant,
            dateInk = colors.onSurface,
            titleInk = colors.onSurface,
            mutedInk = colors.onSurfaceVariant,
            stampInk = colors.onSurface,
            buttonBg = colors.primary,
            buttonFg = colors.onPrimary,
        )
    }
    val isDark = colors.surface.luminance() < 0.5f
    return if (isDark) {
        PocketPalette(
            cardBg = Color(0xFF242019),
            cardBorder = Color(0xFF473E30),
            ledgerLine = Color(0xFF362F24),
            columnRule = Color(0xFF7A4B42),
            pocketBack = Color(0xFF171410),
            pocketFront = Color(0xFF2E271D),
            pocketBorder = Color(0xFF544735),
            pocketStitch = Color(0xFF453A2B),
            dateInk = Color(0xFF8DB2EC),
            titleInk = Color(0xFFEDE5D6),
            mutedInk = Color(0xFFB5A892),
            stampInk = Color(0xFFEF8373),
            buttonBg = Color(0xFFE09578),
            buttonFg = Color(0xFF21130C),
        )
    } else {
        PocketPalette(
            cardBg = Color(0xFFFBF5E6),
            cardBorder = Color(0xFFD6C6A5),
            ledgerLine = Color(0xFFE3D5B8),
            columnRule = Color(0xFFD69F94),
            pocketBack = Color(0xFFD5BE93),
            pocketFront = Color(0xFFECDAB6),
            pocketBorder = Color(0xFFC5AE82),
            pocketStitch = Color(0xFFB89E70),
            dateInk = Color(0xFF2B4C7E),
            titleInk = Color(0xFF201A12),
            mutedInk = Color(0xFF665843),
            stampInk = Color(0xFFA63429),
            buttonBg = Color(0xFF7A4326),
            buttonFg = Color(0xFFFFF9F0),
        )
    }
}

private val DateColumnWidth = 84.dp
private val PocketOverlap = 24.dp
private val CardSideInset = 10.dp
private val StampDiameter = 92.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupportScreen(
    books: List<Book>,
    reading: List<SupportReading>,
    cardStamp: CardStamp?,
    onCardStamp: (CardStamp?) -> Unit,
    onBack: () -> Unit,
    nested: Boolean,
    onSponsor: () -> Unit,
    onStar: () -> Unit,
    onShare: () -> Unit,
    onReport: () -> Unit,
    onRate: (() -> Unit)?,
) {
    val ledger = remember(books, reading) { buildSupportLedger(books, reading) }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_sponsor)) },
                navigationIcon = {
                    if (nested) {
                        NestedPage { PaneBackButton(onBack) }
                    } else {
                        PaneBackButton(onBack)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BackPocketLibraryCard(
                    ledger = ledger,
                    stamp = cardStamp,
                    onStampChange = onCardStamp,
                    onSponsor = onSponsor,
                )
                Text(
                    text = stringResource(R.string.support_ways),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surfaceContainerLow,
                ) {
                    Column {
                        if (onRate != null) {
                            SupportAction(Icons.Outlined.RateReview, R.string.support_rate, R.string.support_rate_detail, onRate)
                            RowDivider()
                        }
                        SupportAction(Icons.Outlined.StarBorder, R.string.support_star, R.string.support_star_detail, onStar)
                        RowDivider()
                        SupportAction(Icons.Outlined.Share, R.string.support_share, R.string.support_share_detail, onShare)
                        RowDivider()
                        SupportAction(Icons.Outlined.BugReport, R.string.support_report, R.string.support_report_detail, onReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackPocketLibraryCard(
    ledger: SupportLedger,
    stamp: CardStamp?,
    onStampChange: (CardStamp?) -> Unit,
    onSponsor: () -> Unit,
) {
    val eInk = LocalEInk.current
    val palette = pocketPalette(MaterialTheme.colorScheme, eInk)
    val pocketBackShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

    PocketStackLayout(
        pocketBack = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(palette.pocketBack, pocketBackShape)
                    .border(1.dp, palette.pocketBorder, pocketBackShape),
            )
        },
        card = {
            CheckoutCard(
                ledger = ledger,
                stamp = stamp,
                palette = palette,
                eInk = eInk,
                onStampChange = onStampChange,
            )
        },
        pocketFront = {
            PocketFront(
                palette = palette,
                onSponsor = onSponsor,
            )
        },
    )
}

@Composable
private fun PocketStackLayout(
    pocketBack: @Composable () -> Unit,
    card: @Composable () -> Unit,
    pocketFront: @Composable () -> Unit,
) {
    Layout(
        content = {
            pocketBack()
            card()
            pocketFront()
        },
    ) { measurables, constraints ->
        val insetPx = CardSideInset.roundToPx()
        val overlapPx = PocketOverlap.roundToPx()
        val backLipRevealPx = 8.dp.roundToPx()
        val fullWidth = constraints.maxWidth
        val cardWidth = (fullWidth - insetPx * 2).coerceAtLeast(0)

        val backPlaceable = measurables[0].measure(
            constraints.copy(minWidth = fullWidth, maxWidth = fullWidth, minHeight = 0),
        )
        val cardPlaceable = measurables[1].measure(
            constraints.copy(minWidth = cardWidth, maxWidth = cardWidth, minHeight = 0),
        )
        val frontPlaceable = measurables[2].measure(
            constraints.copy(minWidth = fullWidth, maxWidth = fullWidth, minHeight = 0),
        )

        val pocketTopY = (cardPlaceable.height - overlapPx).coerceAtLeast(0)
        val backTopY = (pocketTopY - backLipRevealPx).coerceAtLeast(0)
        val totalHeight = pocketTopY + frontPlaceable.height

        layout(fullWidth, totalHeight) {
            backPlaceable.placeRelative(0, backTopY)
            cardPlaceable.placeRelative(insetPx, 0)
            frontPlaceable.placeRelative(0, pocketTopY)
        }
    }
}

@Composable
private fun CheckoutCard(
    ledger: SupportLedger,
    stamp: CardStamp?,
    palette: PocketPalette,
    eInk: Boolean,
    onStampChange: (CardStamp?) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val locale = LocalLocale.current.platformLocale
    val rowDateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }
    val cardShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    var ledgerSize by remember { mutableStateOf(IntSize.Zero) }
    val clearRadiusPx = with(density) { (StampDiameter / 2).toPx() * 1.1f }

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.cardBg, cardShape)
            .border(1.dp, palette.cardBorder, cardShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.support_card_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    color = palette.titleInk,
                )
                if (ledger.bookCount > 0) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.library_book_count,
                                ledger.bookCount,
                                ledger.bookCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.mutedInk,
                        )
                        if (ledger.totalDurationMs > 0L) {
                            Text(
                                text = readingDuration(ledger.totalDurationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.mutedInk,
                            )
                        }
                    }
                }
            }
            InkStampButton(
                stamped = stamp != null,
                palette = palette,
                eInk = eInk,
                onClick = {
                    if (stamp != null) {
                        view.performReaderHaptic(ReaderHaptic.THRESHOLD_DEACTIVATE)
                        onStampChange(null)
                    } else {
                        view.performReaderHaptic(ReaderHaptic.CONFIRM)
                        onStampChange(
                            CardStamp.clamped(
                                x = 0.72f,
                                y = 0.56f,
                                stampedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }

        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val stroke = 1.dp.toPx()
            drawLine(palette.cardBorder, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), stroke)
            drawLine(palette.cardBorder, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { ledgerSize = it }
                .pointerInput(stamp, clearRadiusPx) {
                    detectTapGestures { offset ->
                        val w = ledgerSize.width.toFloat()
                        val h = ledgerSize.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectTapGestures
                        if (stamp != null) {
                            val cx = stamp.x * w
                            val cy = stamp.y * h
                            if (hypot(offset.x - cx, offset.y - cy) <= clearRadiusPx) {
                                view.performReaderHaptic(ReaderHaptic.THRESHOLD_DEACTIVATE)
                                onStampChange(null)
                                return@detectTapGestures
                            }
                        }
                        view.performReaderHaptic(ReaderHaptic.CONFIRM)
                        onStampChange(
                            CardStamp.clamped(
                                x = offset.x / w,
                                y = offset.y / h,
                                stampedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                .drawBehind {
                    val colX = if (layoutDirection == LayoutDirection.Rtl) {
                        size.width - DateColumnWidth.toPx()
                    } else {
                        DateColumnWidth.toPx()
                    }
                    drawLine(
                        color = palette.columnRule,
                        start = Offset(colX, 0f),
                        end = Offset(colX, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        ) {
            Column(Modifier.fillMaxWidth()) {
                LedgerRowFrame(lineColor = palette.ledgerLine, minHeight = 26.dp) {
                    Text(
                        text = stringResource(R.string.support_card_date),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.mutedInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(DateColumnWidth).padding(horizontal = 10.dp),
                    )
                    Text(
                        text = stringResource(R.string.support_card_book),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.mutedInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }

                for (index in 0 until 4) {
                    val row = ledger.rows.getOrNull(index)
                    LedgerRowFrame(lineColor = palette.ledgerLine, minHeight = 38.dp) {
                        if (row != null) {
                            val stampedDate = remember(row.dateMillis, rowDateFormatter) {
                                formatEpochMillis(row.dateMillis, rowDateFormatter)
                            }
                            val jitter = if (eInk) 0f else stampJitterDegrees(row.bookUrl)
                            Text(
                                text = stampedDate,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                ),
                                color = palette.dateInk.copy(alpha = if (eInk) 1f else 0.88f),
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier
                                    .width(DateColumnWidth)
                                    .padding(horizontal = 10.dp)
                                    .rotate(jitter),
                            )
                            Row(
                                Modifier.weight(1f).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = row.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.titleInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (row.durationMs > 0L) {
                                    Text(
                                        text = readingDuration(row.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.mutedInk,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        } else if (index == 0 && ledger.rows.isEmpty()) {
                            Spacer(Modifier.width(DateColumnWidth))
                            Text(
                                text = stringResource(R.string.support_card_empty),
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = palette.mutedInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                        } else {
                            Spacer(Modifier.fillMaxWidth())
                        }
                    }
                }

                LedgerRowFrame(lineColor = palette.ledgerLine, minHeight = 34.dp) {
                    Spacer(Modifier.width(DateColumnWidth))
                    if (stamp == null) {
                        Text(
                            text = stringResource(R.string.support_stamp_hint),
                            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                            color = palette.mutedInk.copy(alpha = if (eInk) 1f else 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(PocketOverlap))
            }

            if (stamp != null && ledgerSize.width > 0 && ledgerSize.height > 0) {
                val radiusPx = with(density) { (StampDiameter / 2).roundToPx() }
                val offsetX = (stamp.x * ledgerSize.width).roundToInt() - radiusPx
                val offsetY = (stamp.y * ledgerSize.height).roundToInt() - radiusPx
                val stampDate = remember(stamp.stampedAt, rowDateFormatter) {
                    formatEpochMillis(stamp.stampedAt, rowDateFormatter).uppercase(locale)
                }
                RubberStampMark(
                    label = stringResource(R.string.support_stamp).uppercase(locale),
                    date = stampDate,
                    stampedAt = stamp.stampedAt,
                    color = palette.stampInk,
                    cardBg = palette.cardBg,
                    eInk = eInk,
                    modifier = Modifier.offset { IntOffset(offsetX, offsetY) },
                )
            }
        }
    }
}

@Composable
private fun LedgerRowFrame(
    lineColor: Color,
    minHeight: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun InkStampButton(
    stamped: Boolean,
    palette: PocketPalette,
    eInk: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (stamped) {
        Color.Transparent
    } else {
        palette.stampInk.copy(alpha = if (eInk) 0.08f else 0.12f)
    }
    val border = if (stamped) palette.cardBorder else palette.stampInk.copy(alpha = 0.55f)
    val fg = if (stamped) palette.mutedInk else palette.stampInk

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (stamped) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = fg,
                )
            } else {
                Canvas(Modifier.size(12.dp)) {
                    drawCircle(fg, radius = size.minDimension / 2f, style = Stroke(1.3.dp.toPx()))
                    drawCircle(fg, radius = size.minDimension * 0.24f)
                }
            }
            Text(
                text = stringResource(if (stamped) R.string.support_stamp_clear else R.string.support_stamp_ink),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

@Composable
private fun RubberStampMark(
    label: String,
    date: String,
    stampedAt: Long,
    color: Color,
    cardBg: Color,
    eInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale = remember(stampedAt) {
        val freshPress = !eInk && kotlin.math.abs(System.currentTimeMillis() - stampedAt) < 1_200L
        Animatable(if (freshPress) 1.2f else 1f)
    }
    LaunchedEffect(stampedAt, eInk) {
        if (scale.value > 1f) {
            scale.animateTo(1f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
        }
    }
    val angle = if (eInk) 0f else (((stampedAt / 97L) % 13L).toInt() - 6) * 1.4f
    val ink = if (eInk) color else color.copy(alpha = 0.88f)
    val labelFontSize = if (label.length > 9) 9.sp else 10.5.sp

    Box(
        modifier = modifier
            .size(StampDiameter)
            .clearAndSetSemantics {}
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = angle
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerStroke = 2.2.dp.toPx()
            val innerStroke = 1.dp.toPx()
            val innerR = r - 5.5.dp.toPx()

            drawCircle(cardBg.copy(alpha = if (eInk) 0.92f else 0.78f), radius = r - outerStroke / 2f, center = center)
            drawCircle(ink, radius = r - outerStroke / 2f, center = center, style = Stroke(outerStroke))
            drawCircle(ink, radius = innerR, center = center, style = Stroke(innerStroke))

            val bandHalfH = size.height * 0.145f
            val chordHalfW = sqrt((innerR * innerR - bandHalfH * bandHalfH).coerceAtLeast(0f))
            drawLine(
                color = ink,
                start = Offset(center.x - chordHalfW, center.y - bandHalfH),
                end = Offset(center.x + chordHalfW, center.y - bandHalfH),
                strokeWidth = innerStroke,
            )
            drawLine(
                color = ink,
                start = Offset(center.x - chordHalfW, center.y + bandHalfH),
                end = Offset(center.x + chordHalfW, center.y + bandHalfH),
                strokeWidth = innerStroke,
            )

            // Small heart mark in the upper segment of the rubber seal.
            val heartY = center.y - innerR * 0.56f
            val hs = 4.2.dp.toPx()
            val heart = Path().apply {
                moveTo(center.x, heartY + hs * 0.85f)
                cubicTo(center.x - hs * 1.3f, heartY + hs * 0.1f, center.x - hs * 0.95f, heartY - hs * 0.85f, center.x, heartY - hs * 0.2f)
                cubicTo(center.x + hs * 0.95f, heartY - hs * 0.85f, center.x + hs * 1.3f, heartY + hs * 0.1f, center.x, heartY + hs * 0.85f)
                close()
            }
            drawPath(heart, ink)
        }

        Text(
            text = label,
            color = ink,
            fontSize = labelFontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )

        Text(
            text = date,
            color = ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 13.dp),
        )
    }
}

@Composable
private fun PocketFront(
    palette: PocketPalette,
    onSponsor: () -> Unit,
) {
    val shape = remember { PocketFrontShape(notchWidth = 78.dp, notchDepth = 15.dp, cornerRadius = 10.dp) }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.pocketFront)
            .drawWithCache {
                val outlinePath = buildPocketFrontPath(
                    size = size,
                    notchWidth = 78.dp.toPx(),
                    notchDepth = 15.dp.toPx(),
                    cornerRadius = 10.dp.toPx(),
                )
                val borderStroke = Stroke(width = 1.dp.toPx())
                val stitchEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                val stitchInset = 9.dp.toPx()
                val stitchTop = 14.dp.toPx()
                val stitchBottom = (size.height - 14.dp.toPx()).coerceAtLeast(stitchTop)
                onDrawWithContent {
                    drawContent()
                    drawLine(
                        color = palette.pocketStitch,
                        start = Offset(stitchInset, stitchTop),
                        end = Offset(stitchInset, stitchBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = stitchEffect,
                    )
                    drawLine(
                        color = palette.pocketStitch,
                        start = Offset(size.width - stitchInset, stitchTop),
                        end = Offset(size.width - stitchInset, stitchBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = stitchEffect,
                    )
                    drawPath(outlinePath, palette.pocketBorder, style = borderStroke)
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.support_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = palette.titleInk,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.support_note),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.titleInk.copy(alpha = 0.9f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_author_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
                Text(
                    text = "Chmouel",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                        fontStyle = FontStyle.Italic,
                    ),
                    color = palette.mutedInk,
                )
            }
            Button(
                onClick = onSponsor,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.buttonBg,
                    contentColor = palette.buttonFg,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.support_github))
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp),
                )
            }
        }
    }
}

private class PocketFrontShape(
    private val notchWidth: androidx.compose.ui.unit.Dp,
    private val notchDepth: androidx.compose.ui.unit.Dp,
    private val cornerRadius: androidx.compose.ui.unit.Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = with(density) {
        Outline.Generic(
            buildPocketFrontPath(
                size = size,
                notchWidth = notchWidth.toPx(),
                notchDepth = notchDepth.toPx(),
                cornerRadius = cornerRadius.toPx(),
            ),
        )
    }
}

private fun buildPocketFrontPath(
    size: Size,
    notchWidth: Float,
    notchDepth: Float,
    cornerRadius: Float,
): Path {
    val w = size.width
    val h = size.height
    val topR = min(cornerRadius * 0.5f, 6f)
    val bottomR = min(cornerRadius, min(w, h) / 2f)
    val cx = w / 2f
    val halfNotch = min(notchWidth / 2f, (w - topR * 4f).coerceAtLeast(0f) * 0.35f)
    val depth = min(notchDepth, h * 0.25f)

    return Path().apply {
        moveTo(0f, topR)
        quadraticTo(0f, 0f, topR, 0f)
        lineTo(cx - halfNotch, 0f)
        cubicTo(
            cx - halfNotch * 0.52f,
            0f,
            cx - halfNotch * 0.45f,
            depth,
            cx,
            depth,
        )
        cubicTo(
            cx + halfNotch * 0.45f,
            depth,
            cx + halfNotch * 0.52f,
            0f,
            cx + halfNotch,
            0f,
        )
        lineTo(w - topR, 0f)
        quadraticTo(w, 0f, w, topR)
        lineTo(w, h - bottomR)
        quadraticTo(w, h, w - bottomR, h)
        lineTo(bottomR, h)
        quadraticTo(0f, h, 0f, h - bottomR)
        close()
    }
}

private fun formatEpochMillis(millis: Long, formatter: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)

private fun stampJitterDegrees(seed: String): Float =
    (((seed.hashCode() and 0x7fffffff) % 5) - 2) * 0.45f

@Composable
private fun SupportAction(icon: ImageVector, title: Int, detail: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConnectionRow(
        icon = {
            Box(
                Modifier.size(24.dp).background(colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSecondaryContainer)
            }
        },
        title = stringResource(title),
        subtitle = stringResource(detail),
        onClick = onClick,
    )
}
