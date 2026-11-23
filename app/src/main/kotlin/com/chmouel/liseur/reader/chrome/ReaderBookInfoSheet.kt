package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.reader.progress.BookScreenEstimate
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.SectionScreens
import com.chmouel.liseur.reader.progress.footerPages
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.languageName
import com.chmouel.liseur.ui.windowWidth
import org.readium.r2.shared.publication.Metadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookInfoSheet(
    book: Book?,
    storedDescription: String?,
    metadata: Metadata,
    displayAuthors: List<String>,
    progress: ReaderProgress?,
    reflowable: Boolean,
    screens: SectionScreens?,
    bookScreens: BookScreenEstimate,
    onDismiss: () -> Unit,
) {
    val eInk = LocalEInk.current
    val locale = LocalConfiguration.current.locales[0]
    val title = book?.title?.trim()?.takeIf { it.isNotEmpty() } ?: metadata.title?.trim().orEmpty()
    val authors = remember(book?.authorOverridden, book?.author, displayAuthors, metadata.authors) {
        if (book?.authorOverridden == true) {
            book.author?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            val primary = displayAuthors.map { it.trim() }.filter { it.isNotEmpty() }
            val raw = if (primary.isNotEmpty()) primary else metadata.authors.map { it.name.trim() }.filter { it.isNotEmpty() }
            raw.joinToString(", ").takeIf { it.isNotEmpty() }
        }
    }
    val publishers = remember(metadata.publishers) {
        metadata.publishers.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() }
    }
    val languages = remember(metadata.languages, locale) {
        metadata.languages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") { languageName(it, locale) }
            .takeIf { it.isNotEmpty() }
    }
    val description = remember(book?.descriptionOverridden, book?.userDescription, storedDescription, metadata.description) {
        if (book?.descriptionOverridden == true) {
            descriptionText(book.userDescription)
        } else {
            descriptionText(storedDescription) ?: descriptionText(metadata.description)
        }
    }
    val pages = remember(reflowable, screens, progress, bookScreens) {
        footerPages(reflowable, screens, progress, bookScreens)
    }

    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (book != null) {
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .width(84.dp)
                            .aspectRatio(2f / 3f),
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (authors != null) {
                        Text(
                            text = authors,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (progress != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.book_details_reading_progress),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val progressSummary = if (pages != null) {
                                val pageLabel = if (pages.exact) {
                                    stringResource(R.string.footer_page, pages.page, pages.pages)
                                } else {
                                    stringResource(R.string.footer_page_about, pages.page, pages.pages)
                                }
                                "${stringResource(R.string.reading_stats_progress, progress.percent)} · $pageLabel"
                            } else {
                                stringResource(R.string.reading_stats_progress, progress.percent)
                            }
                            Text(
                                text = progressSummary,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress.totalProgression.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            trackColor = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        progress.chapterTitle?.takeIf { it.isNotBlank() }?.let { chapter ->
                            Text(
                                text = chapter,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (publishers != null || languages != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (publishers != null) {
                        BookInfoMetaCard(
                            label = stringResource(R.string.book_details_publisher),
                            value = publishers,
                            icon = Icons.Outlined.MenuBook,
                            eInk = eInk,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (languages != null) {
                        BookInfoMetaCard(
                            label = stringResource(R.string.book_details_language),
                            value = languages,
                            icon = Icons.Outlined.Language,
                            eInk = eInk,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (description != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.book_details_description),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookInfoMetaCard(
    label: String,
    value: String,
    icon: ImageVector,
    eInk: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun descriptionText(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val plain = if ('<' in trimmed && '>' in trimmed) {
        HtmlCompat.fromHtml(trimmed, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    } else {
        trimmed
    }
    return plain.trim().takeIf { it.isNotEmpty() }
}
