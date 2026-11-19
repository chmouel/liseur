package com.chmouel.liseur.vocabulary

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.launchPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VocabularyMenu(
    repository: VocabularyRepository,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val resolver = context.contentResolver
    val resources = LocalResources.current

    fun export(uri: Uri?, json: Boolean) {
        if (uri == null || busy) return
        busy = true
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val records = repository.export()
                    checkNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                        if (json) writeVocabularyJson(records, it) else writeVocabularyCsv(records, it)
                    }
                }
                R.string.vocabulary_exported
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                R.string.vocabulary_export_failed
            } finally {
                busy = false
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, true) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                val message = try {
                    val count = withContext(Dispatchers.IO) {
                        val records = checkNotNull(resolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use {
                            readVocabulary(it)
                        }
                        repository.import(records)
                    }
                    resources.getString(R.string.vocabulary_imported, count)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    resources.getString(R.string.vocabulary_import_failed)
                } finally {
                    busy = false
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vocabulary_import)) },
                onClick = {
                    expanded = false
                    import.launchPicker(context, arrayOf("*/*"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vocabulary_export_json)) },
                onClick = {
                    expanded = false
                    json.launchPicker(context, "liseur-vocabulary.json")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vocabulary_export_csv)) },
                onClick = {
                    expanded = false
                    csv.launchPicker(context, "liseur-vocabulary.csv")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}
