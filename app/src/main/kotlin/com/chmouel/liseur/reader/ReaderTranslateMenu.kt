package com.chmouel.liseur.reader

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chmouel.liseur.R
import com.chmouel.liseur.translate.TranslateFeature
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** The book whole-book translation is prepared for from the reader, and whether it already has one. */
internal class ReaderWholeBook(val bookUrl: String, val saved: Flow<Boolean>)

/**
 * The ⋮ menu's translation item, laid out as a DropdownMenuItem is; that
 * one has no long press, which opens whole-book translation here.
 */
@Composable
internal fun ReaderTranslateMenuItem(
    entry: ReaderTranslateEntry,
    uiLocale: Locale,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val label = when (entry) {
        is ReaderTranslateEntry.Into -> {
            val target = entry.languages.target
            // Mid-sentence, as the language names itself: "français", not "Français".
            stringResource(R.string.reader_translate_into, Locale.forLanguageTag(target).getDisplayName(uiLocale).ifBlank { target })
        }
        is ReaderTranslateEntry.SetUp -> stringResource(R.string.reader_translate_book)
    }
    val colors = MenuDefaults.itemColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, maxWidth = 280.dp, minHeight = 48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick.takeIf { entry.wholeBook },
                onLongClickLabel = if (entry.wholeBook) stringResource(R.string.whole_book_prepare) else null,
            )
            .padding(MenuDefaults.DropdownMenuItemContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.leadingIconColor) {
            Box(Modifier.defaultMinSize(minWidth = 24.dp)) {
                Icon(Icons.Outlined.Translate, contentDescription = null)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.textColor,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Translation settings over the reader, as the selected-passage sheet opens them. */
@Composable
internal fun ReaderTranslationSettings(translate: TranslateFeature, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { translate.SettingsScreen(onBack = onClose) }
    }
}
