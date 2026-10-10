package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.translate.TranslationLanguages
import com.chmouel.liseur.ui.LocalEInk

/**
 * The mark in the top corner of a translated page, across from the
 * bookmark ribbon, so the words read as a translation with the reader's
 * controls down. Faint, because the page is what is being read; a tap
 * brings the controls up, where the translation bar has Stop.
 */
@Composable
fun TranslatedPageMark(
    source: String?,
    target: String,
    theme: ReaderTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalConfiguration.current.locales[0]
    val label = stringResource(
        R.string.translation_page_indicator,
        source?.let { TranslationLanguages.name(it, ui) } ?: stringResource(R.string.translation_detected),
        TranslationLanguages.name(target, ui),
    )
    // Electronic paper has no faint grey worth the name.
    val alpha = if (LocalEInk.current) 1f else 0.45f
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(
            Icons.Outlined.Translate,
            contentDescription = null,
            tint = theme.foreground.copy(alpha = alpha),
            modifier = Modifier.padding(top = 10.dp).size(16.dp),
        )
    }
}
