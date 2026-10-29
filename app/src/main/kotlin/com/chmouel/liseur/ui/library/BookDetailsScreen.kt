package com.chmouel.liseur.ui.library

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.BookDetailsBounds
import com.chmouel.liseur.domain.ContributorRoles
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.labelRes
import com.chmouel.liseur.ui.stats.StatsCoverThumbnail
import com.chmouel.liseur.ui.stats.readingDuration
import com.chmouel.liseur.ui.windowWidth
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
 * Everything the app knows about one book, read-only, in five bands.
 * A field with nothing behind it is not drawn, and neither is a band
 * left empty by that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(
    state: BookDetailsUiState,
    etaSeconds: Double?,
    onBack: () -> Unit,
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutBottom())
                .consumeWindowInsets(padding),
            contentAlignment = if (details == null) Alignment.Center else Alignment.TopCenter,
        ) {
            if (details == null) {
                BusyIndicator()
                return@Box
            }
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp + padding.calculateBottomPadding()),
            ) {
                BookBand(details)
                AboutBand(details)
                PublicationBand(details)
                CopyBand(details)
                ReadingBand(details, etaSeconds, onOpenStats)
            }
        }
    }
}

@Composable
private fun BookBand(details: BookDetails) {
    val book = details.book
    val meta = details.metadata
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatsCoverThumbnail(
            title = book.displayTitle,
            coverPath = book.coverPath,
            coverUrl = book.coverUrl,
            browseServerId = book.browseServerId,
            modifier = Modifier.width(80.dp).height(120.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            meta.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val byRole = meta.contributors.groupBy({ it.role }, { it.name })
    val authors = byRole[ContributorRoles.AUTHOR] ?: listOfNotNull(book.author?.takeIf { it.isNotBlank() })
    if (authors.isNotEmpty()) Field(stringResource(R.string.book_details_role_author), authors.joinToString(", "))
    ROLE_LABELS.forEach { (role, label) ->
        byRole[role]?.let { Field(stringResource(label), it.joinToString(", ")) }
    }
    book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
        val index = seriesIndexLabel(book.seriesIndex)
        Field(
            stringResource(R.string.book_details_series),
            if (index == null) series else stringResource(R.string.series_position, series, index),
        )
    }
}

@Composable
private fun AboutBand(details: BookDetails) {
    val meta = details.metadata
    if (meta.description == null && meta.subjects.isEmpty()) return
    Band(stringResource(R.string.book_details_about)) {
        meta.description?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (meta.subjects.isNotEmpty()) {
            Field(stringResource(R.string.book_details_subjects), meta.subjects.joinToString(", "))
        }
    }
}

@Composable
private fun PublicationBand(details: BookDetails) {
    val meta = details.metadata
    val locale = currentLocale()
    val a11y = meta.accessibility
    if (meta.publishers.isEmpty() && meta.imprints.isEmpty() && meta.published == null && meta.languages.isEmpty() &&
        meta.identifiers.isEmpty() && meta.pageCount == null && a11y == null
    ) {
        return
    }
    Band(stringResource(R.string.book_details_publication)) {
        if (meta.publishers.isNotEmpty()) {
            Field(stringResource(R.string.book_details_publisher), meta.publishers.joinToString(", "))
        }
        if (meta.imprints.isNotEmpty()) {
            Field(stringResource(R.string.book_details_imprint), meta.imprints.joinToString(", "))
        }
        meta.published?.let { Field(stringResource(R.string.book_details_published), publishedDate(it, locale)) }
        if (meta.languages.isNotEmpty()) {
            Field(
                stringResource(R.string.book_details_language),
                meta.languages.joinToString(", ") { languageName(it, locale) },
            )
        }
        meta.identifiers.forEach { id ->
            val named = id.scheme in NAMED_SCHEMES
            Field(
                if (named) id.scheme.uppercase(Locale.ROOT) else stringResource(R.string.book_details_identifier),
                if (named || id.scheme.isEmpty()) id.value else "${id.scheme}:${id.value}",
                code = true,
            )
        }
        meta.pageCount?.let {
            Field(stringResource(R.string.book_details_pages), String.format(locale, "%d", it))
        }
        a11y?.let { AccessibilityFields(it) }
    }
}

@Composable
private fun AccessibilityFields(a11y: BookAccessibility) {
    val conformance = a11y.conformsTo.mapNotNull(::wcagConformance).distinct()
    val features = FEATURE_LABELS.filterKeys { it in a11y.features }.values.distinct()
    val hazards = HAZARD_LABELS.filterKeys { it in a11y.hazards }.values
    val noHazards = hazards.isEmpty() &&
        ("none" in a11y.hazards || a11y.hazards.containsAll(listOf("noFlashingHazard", "noMotionSimulationHazard", "noSoundHazard")))
    if (conformance.isEmpty() && features.isEmpty() && hazards.isEmpty() && !noHazards &&
        a11y.summary == null && a11y.certifiedBy == null
    ) {
        return
    }
    Text(
        stringResource(R.string.book_details_accessibility),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
    if (conformance.isNotEmpty()) {
        Field(
            stringResource(R.string.book_details_a11y_conforms),
            conformance.map { (version, level) -> stringResource(R.string.book_details_a11y_wcag, version, level) }
                .joinToString("\n"),
        )
    }
    if (features.isNotEmpty()) {
        Field(stringResource(R.string.book_details_a11y_features), features.map { stringResource(it) }.joinToString(", "))
    }
    if (hazards.isNotEmpty() || noHazards) {
        Field(
            stringResource(R.string.book_details_a11y_hazards),
            if (noHazards) {
                stringResource(R.string.book_details_a11y_no_hazards)
            } else {
                hazards.map { stringResource(it) }.joinToString(", ")
            },
        )
    }
    a11y.summary?.let { Field(stringResource(R.string.book_details_a11y_summary), it) }
    a11y.certifiedBy?.let { Field(stringResource(R.string.book_details_a11y_certified_by), it) }
}

@Composable
private fun CopyBand(details: BookDetails) {
    val book = details.book
    val context = LocalContext.current
    val locale = currentLocale()
    val onDevice = book.openableUrl != null
    Band(stringResource(R.string.book_details_this_copy)) {
        Field(stringResource(R.string.book_details_format), stringResource(R.string.book_details_format_epub))
        val measured = details.metadata.fileSizeBytes
        val claimed = book.sizeBytes?.takeIf { it in 0..BookDetailsBounds.MAX_BYTES }
        measured?.let {
            Field(stringResource(R.string.book_details_size), Formatter.formatShortFileSize(context, it))
        }
        // What the server claims is labelled as its claim, and only drawn
        // when it adds something to what was measured here.
        claimed?.takeIf { it != measured }?.let {
            Field(stringResource(R.string.book_details_server_size), Formatter.formatShortFileSize(context, it))
        }
        Field(stringResource(R.string.book_details_from), provenanceLabel(details))
        Field(stringResource(R.string.book_details_added), day(book.addedAt, locale))
        if (onDevice) {
            book.downloadedAt?.let { Field(stringResource(R.string.book_details_downloaded), day(it, locale)) }
            book.fileModifiedAt?.takeIf { it > 0 }?.let {
                Field(stringResource(R.string.book_details_file_modified), day(it, locale))
            }
        }
        details.fingerprint?.let {
            Field(stringResource(R.string.book_details_sha256), it.sha256, code = true)
            Field(stringResource(R.string.book_details_partial_md5), it.partialMd5, code = true)
        }
    }
}

@Composable
private fun ReadingBand(details: BookDetails, etaSeconds: Double?, onOpenStats: () -> Unit) {
    val book = details.book
    val locale = currentLocale()
    Band(stringResource(R.string.book_details_reading)) {
        details.progression?.takeIf { !book.finished && it > 0 }?.let {
            Field(
                stringResource(R.string.book_details_progress),
                stringResource(R.string.reading_stats_progress, (it * 100).toInt()),
            )
        }
        etaSeconds?.takeIf { !book.finished }?.let { seconds ->
            Field(
                stringResource(R.string.reading_stats_time_left_label),
                stringResource(
                    R.string.reading_stats_time_left_value,
                    readingDuration(TimeUnit.SECONDS.toMillis(seconds.toLong())),
                ),
            )
        }
        val status = listOfNotNull(
            book.finishedAt?.let { stringResource(R.string.book_details_finished_on, day(it, locale)) },
            book.archivedAt?.let { stringResource(R.string.book_details_archived_on, day(it, locale)) },
        )
        if (status.isNotEmpty()) Field(stringResource(R.string.book_details_status), status.joinToString("\n"))
        if (details.annotations > 0) {
            Field(stringResource(R.string.book_details_annotations), String.format(locale, "%d", details.annotations))
        }
        OutlinedButton(
            onClick = onOpenStats,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.reading_stats))
        }
    }
}

@Composable
private fun Band(title: String, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
}

/**
 * One label and its value, read out as one node. A [code] value (an id,
 * a hash) is selectable and kept left to right whatever the language
 * around it.
 */
@Composable
private fun Field(label: String, value: String, code: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (code) {
            SelectionContainer {
                Text(
                    "\u2066$value\u2069",
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                )
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodyLarge)
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

private val ROLE_LABELS = listOf(
    ContributorRoles.TRANSLATOR to R.string.book_details_role_translator,
    ContributorRoles.EDITOR to R.string.book_details_role_editor,
    ContributorRoles.ILLUSTRATOR to R.string.book_details_role_illustrator,
    ContributorRoles.NARRATOR to R.string.book_details_role_narrator,
    ContributorRoles.CONTRIBUTOR to R.string.book_details_role_contributor,
)

private val FEATURE_LABELS = linkedMapOf(
    "alternativeText" to R.string.book_details_a11y_feature_alt_text,
    "tableOfContents" to R.string.book_details_a11y_feature_toc,
    "structuralNavigation" to R.string.book_details_a11y_feature_structure,
    "printPageNumbers" to R.string.book_details_a11y_feature_page_numbers,
    "pageNavigation" to R.string.book_details_a11y_feature_page_numbers,
    "displayTransformability" to R.string.book_details_a11y_feature_display,
    "synchronizedAudioText" to R.string.book_details_a11y_feature_audio,
)

private val HAZARD_LABELS = linkedMapOf(
    "flashing" to R.string.book_details_a11y_hazard_flashing,
    "motionSimulation" to R.string.book_details_a11y_hazard_motion,
    "sound" to R.string.book_details_a11y_hazard_sound,
)
