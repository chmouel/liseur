package com.chmouel.liseur.ui.reading

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.settings.ImportResult
import com.chmouel.liseur.data.settings.ReadingFont
import com.chmouel.liseur.data.settings.RemovalResult
import com.chmouel.liseur.data.settings.fonts.UserFont
import kotlinx.coroutines.launch

/** The imported fonts, and the two things a reader can do to them. */
@Immutable
data class FontLibraryState(
    val fonts: List<UserFont>,
    val pick: () -> Unit,
    val remove: (UserFont) -> Unit,
)

/**
 * The imported fonts, wired to the picker and to a message for every
 * outcome.
 *
 * Shared by the reader's typography sheet and Settings → Reading
 * appearance so the two cannot drift. [onSelected] is what routes a
 * successful import to the right place — the caller already knows whether
 * this book has been set apart — because **reloading the declarations is
 * not choosing**: someone who has just picked a font file out of a file
 * manager plainly means to read in it, and stopping at "it is now
 * available" would look like nothing happened.
 */
@Composable
fun rememberFontLibrary(onSelected: (ReadingFont) -> Unit): FontLibraryState {
    val context = LocalContext.current
    // Resources rather than the context: reading a resource value off
    // LocalContext does not track configuration changes, and lint is
    // right to say so.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.userFonts }
    val fonts by repository.fonts.collectAsStateWithLifecycle()

    val say = { message: String ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val pick = rememberFontPicker { uri, name ->
        scope.launch {
            when (val result = repository.import(uri, name)) {
                is ImportResult.Imported -> {
                    onSelected(ReadingFont.fromId(result.id))
                    val added = repository.fonts.value.firstOrNull { it.id == result.id }
                    say(resources.getString(R.string.reader_font_imported, added?.displayName ?: ""))
                }
                // Re-picking a font already held has to land on it too, or
                // the second attempt looks like a failure.
                is ImportResult.AlreadyPresent -> {
                    onSelected(ReadingFont.fromId(result.id))
                    val held = repository.fonts.value.firstOrNull { it.id == result.id }
                    say(
                        resources.getString(
                            R.string.reader_font_already_present,
                            held?.displayName ?: "",
                        ),
                    )
                }
                ImportResult.NotAFont -> say(resources.getString(R.string.reader_font_error_not_a_font))
                ImportResult.TooLarge -> say(resources.getString(R.string.reader_font_error_too_large))
                ImportResult.TooMany -> say(resources.getString(R.string.reader_font_error_too_many))
                ImportResult.Unreadable ->
                    say(resources.getString(R.string.reader_font_error_unreadable))
                ImportResult.StorageFailed ->
                    say(resources.getString(R.string.reader_font_error_storage))
            }
        }
    }

    return FontLibraryState(
        fonts = fonts,
        pick = pick,
        remove = { font ->
            scope.launch {
                when (repository.remove(font.id)) {
                    // The selection is deliberately left alone. It stays
                    // the raw id, dormant, and the reader falls back to
                    // the default until the same file comes back.
                    RemovalResult.Removed -> Unit
                    RemovalResult.NotFound, RemovalResult.InvalidId -> Unit
                    RemovalResult.DeleteFailed, RemovalResult.IndexFailed ->
                        say(resources.getString(R.string.reader_font_error_remove))
                }
            }
        },
    )
}
