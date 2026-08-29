package com.chmouel.liseur.ui.reading

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The types offered when picking a font.
 *
 * `application/octet-stream` is in the list on purpose. Android's
 * `MimeTypeMap` has no entry for `ttf` or `otf`, so most
 * DocumentsProviders report a font as a nameless binary, and a filter
 * without it greys out the very files the reader came to find. Nothing is
 * trusted on the strength of it: what the file *is* gets decided by
 * reading its magic, never by what the provider claimed.
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/sfnt",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/font-sfnt",
    "application/octet-stream",
)

/**
 * Opens the system file picker and hands back what was chosen.
 *
 * The display name is looked up here rather than by the caller because
 * the content URI is only readable while the grant lasts. It is a
 * fallback for the font's name and nothing more — a font that names
 * itself is never called after its file.
 */
@Composable
fun rememberFontPicker(onPicked: (Uri, String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        onPicked(uri, name)
    }
    return remember(launcher) { { launcher.launch(FONT_MIME_TYPES) } }
}
