package com.chmouel.liseur.ui.library

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.BookDetailsBounds
import com.chmouel.liseur.domain.ContributorRoles
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.validSeriesPosition
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.settings.labelRes
import com.chmouel.liseur.ui.stats.readingDuration
import com.chmouel.liseur.ui.withoutBottom
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Everything the app knows about one book. The book itself and what to do
 * with it come first; what the publisher said follows; the file behind it
 * waits folded at the bottom. A field with nothing behind it is not drawn,
 * and neither is a section left empty by that.
 *
 * The body is a lazy list keyed by section so that metadata arriving while
 * the screen is open (a download finishing) grows sections without moving
 * the one the reader is looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(
    state: BookDetailsUiState,
    etaSeconds: Double?,
    canDownload: Boolean,
    download: DownloadProgress?,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onOpenStats: () -> Unit,
) {
    if (state is BookDetailsUiState.Gone) {
        LaunchedEffect(Unit) { onBack() }
    }
    val details = (state as? BookDetailsUiState.Ready)?.details
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.book_details),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutBottom())
                .consumeWindowInsets(padding),
            contentAlignment = if (details == null) Alignment.Center else Alignment.TopCenter,
        ) {
            if (details == null) {
                BusyIndicator()
                return@BoxWithConstraints
            }
            val wide = maxWidth >= WIDE_LAYOUT
            val actions = @Composable {
                BookActions(details, canDownload, download, onRead, onDownload, onOpenStats)
            }
            val status = readingStatus(details, etaSeconds)
            val section = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()
            LazyColumn(
                Modifier.fillMaxSize(),
                state = rememberLazyListState(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 32.dp + padding.calculateBottomPadding(),
                ),
            ) {
                item(key = "header") {
                    Header(details, wide, section) {
                        if (wide) {
                            Column(Modifier.padding(top = 20.dp).widthIn(max = WIDE_ACTIONS_WIDTH)) {
                                actions()
                                if (status.isNotEmpty()) ReadingStatusCard(status, Modifier.padding(top = 16.dp))
                            }
                        }
                    }
                }
                if (!wide) {
                    item(key = "actions") { Column(section.padding(top = 20.dp)) { actions() } }
                    if (status.isNotEmpty()) {
                        item(key = "status") { ReadingStatusCard(status, section.padding(top = 16.dp)) }
                    }
                }
                details.metadata.description?.let { description ->
                    item(key = "about") { Description(details.book.url, description, section.padding(top = 24.dp)) }
                }
                if (details.metadata.subjects.isNotEmpty()) {
                    item(key = "subjects") {
                        SubjectTags(details.metadata.subjects, section.padding(top = 20.dp))
                    }
                }
                if (hasPublication(details)) {
                    item(key = "publication") { Publication(details, section) }
                }
                item(key = "fileinfo") { FileInfo(details, section) }
            }
        }
    }
}

@Composable
private fun Header(
    details: BookDetails,
    wide: Boolean,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    val book = details.book
    val meta = details.metadata
    val locale = currentLocale()
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 18.dp)) {
        BookCover(book, Modifier.width(if (wide) 140.dp else 116.dp).aspectRatio(2f / 3f))
        Column(Modifier.weight(1f)) {
            book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                val index = seriesIndexLabel(validSeriesPosition(book.seriesIndex))
                Text(
                    if (index == null) series else stringResource(R.string.series_position, series, index),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                book.displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            meta.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val byRole = meta.contributors.groupBy({ it.role }, { it.name })
            val authors = byRole[ContributorRoles.AUTHOR] ?: listOfNotNull(book.author?.takeIf { it.isNotBlank() })
            if (authors.isNotEmpty()) {
                Text(
                    authors.joinToString(", "),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            CREDIT_LABELS.forEach { (role, label) ->
                byRole[role]?.let { names ->
                    Column(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {}) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(names.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val facts = listOfNotNull(
                meta.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { languageName(it, locale) },
                meta.published?.take(4),
            )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            trailing()
        }
    }
}

/**
 * Two rows that never change in number or order as a download moves
 * along, so the screen does not jump: what to do with the book, then its
 * reading stats.
 */
@Composable
private fun BookActions(
    details: BookDetails,
    canDownload: Boolean,
    download: DownloadProgress?,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val book = details.book
    val tall = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val state = book.downloadState
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            book.openableUrl != null -> Button(onClick = onRead, modifier = tall) {
                ButtonIcon(Icons.AutoMirrored.Outlined.MenuBook)
                Text(
                    stringResource(
                        when {
                            book.finished -> R.string.book_details_read_again
                            (details.progression ?: 0.0) > 0.0 -> R.string.continue_reading
                            else -> R.string.book_details_read
                        },
                    ),
                )
            }
            download != null || state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING ->
                FilledTonalButton(onClick = {}, enabled = false, modifier = tall) {
                    BusyIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (download?.queued ?: (state == DownloadState.QUEUED)) {
                                R.string.state_download_queued
                            } else {
                                R.string.download_in_progress
                            },
                        ),
                    )
                }
            canDownload && book.downloadHref != null -> Button(onClick = onDownload, modifier = tall) {
                ButtonIcon(Icons.Outlined.CloudDownload)
                Text(
                    stringResource(
                        if (state == DownloadState.FAILED) R.string.book_details_retry_download else R.string.download_book,
                    ),
                )
            }
            !canDownload -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = tall) {
                    Text(stringResource(R.string.download_book))
                }
                Text(
                    stringResource(R.string.downloads_not_allowed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> OutlinedButton(onClick = {}, enabled = false, modifier = tall) {
                Text(stringResource(R.string.endpaper_unavailable))
            }
        }
        OutlinedButton(onClick = onOpenStats, modifier = tall) {
            ButtonIcon(Icons.Outlined.Insights)
            Text(stringResource(R.string.reading_stats))
        }
    }
}

@Composable
private fun ButtonIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** One line of the reading card; the card is drawn only when one exists. */
private sealed interface StatusLine {
    data class Progress(val fraction: Double) : StatusLine
    data class TimeLeft(val seconds: Double) : StatusLine
    data class Finished(val at: Long?) : StatusLine
    data class Archived(val at: Long) : StatusLine
    data class Annotations(val count: Int) : StatusLine
}

private fun readingStatus(details: BookDetails, etaSeconds: Double?): List<StatusLine> {
    val book = details.book
    return listOfNotNull(
        details.progression?.takeIf { !book.finished && it > 0 }?.let { StatusLine.Progress(it) },
        etaSeconds?.takeIf { !book.finished }?.let { StatusLine.TimeLeft(it) },
        if (book.finished || book.finishedAt != null) StatusLine.Finished(book.finishedAt) else null,
        book.archivedAt?.let { StatusLine.Archived(it) },
        details.annotations.takeIf { it > 0 }?.let { StatusLine.Annotations(it) },
    )
}

@Composable
private fun ReadingStatusCard(lines: List<StatusLine>, modifier: Modifier) {
    val eInk = LocalEInk.current
    val locale = currentLocale()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lines.forEach { line ->
                when (line) {
                    is StatusLine.Progress -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.reading_stats_progress, (line.fraction * 100).toInt()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = { line.fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            trackColor = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                    is StatusLine.TimeLeft -> Field(
                        stringResource(R.string.reading_stats_time_left_label),
                        stringResource(
                            R.string.reading_stats_time_left_value,
                            readingDuration(TimeUnit.SECONDS.toMillis(line.seconds.toLong())),
                        ),
                        padded = false,
                    )
                    is StatusLine.Finished -> Text(
                        line.at?.let { stringResource(R.string.book_details_finished_on, day(it, locale)) }
                            ?: stringResource(R.string.state_finished),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is StatusLine.Archived -> Text(
                        stringResource(R.string.book_details_archived_on, day(line.at, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is StatusLine.Annotations -> Field(
                        stringResource(R.string.book_details_annotations),
                        String.format(locale, "%d", line.count),
                        padded = false,
                    )
                }
            }
        }
    }
}

/** The blurb, folded to a few lines when long; it speaks for itself without a heading. */
@Composable
private fun Description(bookUrl: String, text: String, modifier: Modifier) {
    var expanded by rememberSaveable(bookUrl) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    // Folded, a blank line between paragraphs could be the last line shown,
    // leaving an ellipsis alone on it.
    val folded = remember(text) { text.replace(BLANK_LINES, "\n") }
    Column(modifier) {
        Text(
            if (expanded) text else folded,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (expanded) R.string.book_details_show_less else R.string.book_details_read_more))
            }
        }
    }
}

@Composable
private fun SubjectTags(subjects: List<String>, modifier: Modifier) {
    val eInk = LocalEInk.current
    Column(modifier) {
        Text(
            stringResource(R.string.book_details_subjects),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            subjects.forEach { subject ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        subjectLabel(subject),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (eInk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** A Library of Congress heading reads as a tag: "Vampires -- Fiction" becomes "Vampires · Fiction". */
internal fun subjectLabel(subject: String): String =
    subject.split("--").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { subject }

private fun hasPublication(details: BookDetails): Boolean {
    val meta = details.metadata
    return meta.publishers.isNotEmpty() || meta.imprints.isNotEmpty() || meta.published != null ||
        meta.languages.isNotEmpty() || meta.identifiers.isNotEmpty() || meta.pageCount != null ||
        meta.contributors.any { it.role == ContributorRoles.CONTRIBUTOR }
}

@Composable
private fun Publication(details: BookDetails, modifier: Modifier) {
    val meta = details.metadata
    val locale = currentLocale()
    Column(modifier) {
        SectionDivider()
        Text(
            stringResource(R.string.book_details_publication),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        FieldGrid(
            listOfNotNull(
                meta.publishers.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.book_details_publisher) to it.joinToString(", ") },
                meta.imprints.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.book_details_imprint) to it.joinToString(", ") },
                meta.published?.let { stringResource(R.string.book_details_published) to publishedDate(it, locale) },
                meta.languages.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.book_details_language) to it.joinToString(", ") { tag -> languageName(tag, locale) }
                },
                meta.pageCount?.let { stringResource(R.string.book_details_pages) to String.format(locale, "%d", it) },
            ),
        )
        meta.contributors.filter { it.role == ContributorRoles.CONTRIBUTOR }.takeIf { it.isNotEmpty() }?.let {
            Field(stringResource(R.string.book_details_role_contributor), it.joinToString(", ") { c -> c.name })
        }
        meta.identifiers.forEach { id ->
            val named = id.scheme in NAMED_SCHEMES
            Field(
                if (named) id.scheme.uppercase(Locale.ROOT) else stringResource(R.string.book_details_identifier),
                if (named || id.scheme.isEmpty()) id.value else "${id.scheme}:${id.value}",
                code = true,
            )
        }
    }
}

/**
 * Where this copy came from and what the file is, folded away by default:
 * a reader rarely needs a hash, but the one who does finds it here.
 */
@Composable
private fun FileInfo(details: BookDetails, modifier: Modifier) {
    val book = details.book
    val context = LocalContext.current
    val locale = currentLocale()
    var open by rememberSaveable(book.url) { mutableStateOf(false) }
    val measured = details.metadata.fileSizeBytes
    val claimed = book.sizeBytes?.takeIf { it in 0..BookDetailsBounds.MAX_BYTES }
    val from = provenanceLabel(details)
    val format = stringResource(R.string.book_details_format_epub)
    val title = stringResource(R.string.book_details_file_info)
    val stateText = stringResource(if (open) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    Column(modifier) {
        SectionDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = stateText
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!open) {
                    val size = (measured ?: claimed)?.let { Formatter.formatShortFileSize(context, it) }
                    Text(
                        listOfNotNull(from, format, size).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!open) return@Column
        val eInk = LocalEInk.current
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
            border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        ) {
            Column(Modifier.padding(16.dp)) {
                val onDevice = book.openableUrl != null
                FieldGrid(
                    listOfNotNull(
                        stringResource(R.string.book_details_from) to from,
                        stringResource(R.string.book_details_format) to format,
                        measured?.let { stringResource(R.string.book_details_size) to Formatter.formatShortFileSize(context, it) },
                        // What the server claims is labelled as its claim, and only drawn
                        // when it adds something to what was measured here.
                        claimed?.takeIf { it != measured }?.let {
                            stringResource(R.string.book_details_server_size) to Formatter.formatShortFileSize(context, it)
                        },
                        stringResource(R.string.book_details_added) to day(book.addedAt, locale),
                        book.downloadedAt?.takeIf { onDevice }?.let {
                            stringResource(R.string.book_details_downloaded) to day(it, locale)
                        },
                        book.fileModifiedAt?.takeIf { onDevice && it > 0 }?.let {
                            stringResource(R.string.book_details_file_modified) to day(it, locale)
                        },
                    ),
                )
                details.fingerprint?.let {
                    Field(stringResource(R.string.book_details_sha256), it.sha256, code = true)
                    Field(stringResource(R.string.book_details_partial_md5), it.partialMd5, code = true)
                }
                details.metadata.accessibility?.let(::accessibilityView)?.takeUnless { it.isEmpty }?.let {
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    AccessibilityFields(it)
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Short facts side by side when there is room for two, one under the other when not. */
@Composable
private fun FieldGrid(fields: List<Pair<String, String>>) {
    if (fields.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= TWO_COLUMN_GRID) 2 else 1
        Column {
            fields.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (label, value) ->
                        Field(label, value, modifier = Modifier.weight(1f))
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * What the screen draws of an accessibility record, worked out once so
 * the band is shown exactly when one of these lines is.
 */
internal data class AccessibilityView(
    val conformance: List<Pair<String, String>> = emptyList(),
    val features: List<Int> = emptyList(),
    val hazards: List<Int> = emptyList(),
    val noHazards: Boolean = false,
    val modes: List<Int> = emptyList(),
    val sufficient: List<List<Int>> = emptyList(),
    val exemptions: List<Int> = emptyList(),
    val summary: String? = null,
    val certifiedBy: String? = null,
    val certifierReport: String? = null,
) {
    val isEmpty: Boolean
        get() = this == AccessibilityView()
}

internal fun accessibilityView(a11y: BookAccessibility): AccessibilityView {
    val hazards = HAZARD_LABELS.filterKeys { it in a11y.hazards }.values.toList()
    return AccessibilityView(
        conformance = a11y.conformsTo.mapNotNull(::wcagConformance).distinct(),
        features = FEATURE_LABELS.filterKeys { it in a11y.features }.values.distinct(),
        hazards = hazards,
        noHazards = hazards.isEmpty() &&
            ("none" in a11y.hazards ||
                a11y.hazards.containsAll(listOf("noFlashingHazard", "noMotionSimulationHazard", "noSoundHazard"))),
        modes = MODE_LABELS.filterKeys { it in a11y.accessModes }.values.toList(),
        sufficient = a11y.accessModesSufficient
            .map { set -> set.split(',').mapNotNull { MODE_LABELS[it] } }
            .filter { it.isNotEmpty() }
            .distinct(),
        exemptions = EXEMPTION_LABELS.filterKeys { it in a11y.exemptions }.values.toList(),
        summary = a11y.summary,
        certifiedBy = a11y.certifiedBy,
        certifierReport = a11y.certifierReport,
    )
}

@Composable
private fun AccessibilityFields(view: AccessibilityView) {
    Text(
        stringResource(R.string.book_details_accessibility),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
    if (view.conformance.isNotEmpty()) {
        Field(
            stringResource(R.string.book_details_a11y_conforms),
            view.conformance.map { (version, level) -> stringResource(R.string.book_details_a11y_wcag, version, level) }
                .joinToString("\n"),
        )
    }
    if (view.modes.isNotEmpty()) {
        Field(stringResource(R.string.book_details_a11y_modes), view.modes.map { stringResource(it) }.joinToString(", "))
    }
    if (view.sufficient.isNotEmpty()) {
        Field(
            stringResource(R.string.book_details_a11y_sufficient),
            view.sufficient.map { set -> set.map { stringResource(it) }.joinToString(" + ") }.joinToString("\n"),
        )
    }
    if (view.features.isNotEmpty()) {
        Field(stringResource(R.string.book_details_a11y_features), view.features.map { stringResource(it) }.joinToString(", "))
    }
    if (view.hazards.isNotEmpty() || view.noHazards) {
        Field(
            stringResource(R.string.book_details_a11y_hazards),
            if (view.noHazards) {
                stringResource(R.string.book_details_a11y_no_hazards)
            } else {
                view.hazards.map { stringResource(it) }.joinToString(", ")
            },
        )
    }
    if (view.exemptions.isNotEmpty()) {
        Field(
            stringResource(R.string.book_details_a11y_exemption),
            view.exemptions.map { stringResource(it) }.joinToString(", "),
        )
    }
    view.summary?.let { Field(stringResource(R.string.book_details_a11y_summary), it) }
    view.certifiedBy?.let { Field(stringResource(R.string.book_details_a11y_certified_by), it) }
    view.certifierReport?.let { Field(stringResource(R.string.book_details_a11y_certifier_report), it, code = true) }
}

/**
 * One label and its value, read out as one node. A [code] value (an id,
 * a hash) is selectable and kept left to right whatever the language
 * around it.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    code: Boolean = false,
    padded: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = if (padded) 6.dp else 0.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (code) {
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                )
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun provenanceLabel(details: BookDetails): String = when (val origin = details.provenance) {
    is BookProvenance.Server -> stringResource(origin.kind.labelRes())
    BookProvenance.Opds -> stringResource(R.string.book_details_from_opds)
    BookProvenance.Grimmory -> stringResource(R.string.book_details_from_grimmory)
    is BookProvenance.Folder -> details.folderName ?: stringResource(R.string.book_details_from_folder)
    BookProvenance.File -> stringResource(R.string.book_details_from_file)
}

@Composable
private fun currentLocale(): Locale = LocalLocale.current.platformLocale

private fun day(millis: Long, locale: Locale): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** A publication date at the precision it was given: a year, a month, or a day. */
private fun publishedDate(iso: String, locale: Locale): String = when (iso.length) {
    4 -> iso
    7 -> YearMonth.parse(iso).format(
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale),
    )
    else -> runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    }.getOrDefault(iso)
}

/** A language tag by its name, script and region kept; a tag that names nothing is shown as written. */
internal fun languageName(tag: String, locale: Locale): String {
    val parsed = Locale.forLanguageTag(tag)
    val language = parsed.language
    if (language.isEmpty() || parsed.getDisplayLanguage(locale).equals(language, ignoreCase = true)) return tag
    val name = parsed.getDisplayName(locale)
    return name.replaceFirstChar { it.titlecase(locale) }
}

/**
 * The WCAG version and level an EPUB accessibility profile claims. The
 * 2017 profile predates versioned names and meant WCAG 2.0.
 */
internal fun wcagConformance(profile: String): Pair<String, String>? {
    val fragment = profile.substringAfter('#', "")
    val match = WCAG.matchEntire(fragment) ?: return null
    val version = match.groupValues[1].ifEmpty { "2.0" }
    return version to match.groupValues[2].uppercase(Locale.ROOT)
}

private val WCAG = Regex("""wcag-(?:(\d\.\d)-)?(a{1,3})""")

private val NAMED_SCHEMES = setOf("isbn", "issn", "doi", "asin", "uuid")

/** Credits named under the author; plain contributors go with the publication. */
private val CREDIT_LABELS = listOf(
    ContributorRoles.TRANSLATOR to R.string.book_details_role_translator,
    ContributorRoles.EDITOR to R.string.book_details_role_editor,
    ContributorRoles.ILLUSTRATOR to R.string.book_details_role_illustrator,
    ContributorRoles.NARRATOR to R.string.book_details_role_narrator,
)

private val WIDE_LAYOUT = 600.dp
private val WIDE_ACTIONS_WIDTH = 420.dp
private val MAX_CONTENT_WIDTH = 840.dp
private val TWO_COLUMN_GRID = 300.dp
private const val DESCRIPTION_LINES = 6
private val BLANK_LINES = Regex("""\n\s*\n""")

private val FEATURE_LABELS = linkedMapOf(
    "alternativeText" to R.string.book_details_a11y_feature_alt_text,
    "tableOfContents" to R.string.book_details_a11y_feature_toc,
    "structuralNavigation" to R.string.book_details_a11y_feature_structure,
    "printPageNumbers" to R.string.book_details_a11y_feature_page_numbers,
    "pageNavigation" to R.string.book_details_a11y_feature_page_numbers,
    "displayTransformability" to R.string.book_details_a11y_feature_display,
    "synchronizedAudioText" to R.string.book_details_a11y_feature_audio,
)

private val MODE_LABELS = linkedMapOf(
    "textual" to R.string.book_details_a11y_mode_textual,
    "visual" to R.string.book_details_a11y_mode_visual,
    "auditory" to R.string.book_details_a11y_mode_auditory,
    "tactile" to R.string.book_details_a11y_mode_tactile,
)

private val EXEMPTION_LABELS = linkedMapOf(
    "eaa-disproportionate-burden" to R.string.book_details_a11y_exemption_burden,
    "eaa-fundamental-alteration" to R.string.book_details_a11y_exemption_alteration,
    "eaa-microenterprise" to R.string.book_details_a11y_exemption_micro,
)

private val HAZARD_LABELS = linkedMapOf(
    "flashing" to R.string.book_details_a11y_hazard_flashing,
    "motionSimulation" to R.string.book_details_a11y_hazard_motion,
    "sound" to R.string.book_details_a11y_hazard_sound,
)
