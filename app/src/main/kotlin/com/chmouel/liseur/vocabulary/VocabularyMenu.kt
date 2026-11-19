package com.chmouel.liseur.vocabulary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.launchPicker
import com.chmouel.liseur.ui.settings.SwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VocabularyMenu(
    repository: VocabularyRepository,
    settings: AppSettings,
    preferences: AppSettingsRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
                    showSettings = true
                },
            )
        }
    }
    if (showSettings) {
        VocabularySettingsDialog(settings, preferences, { showSettings = false })
    }
}

@Composable
internal fun VocabularySettingsDialog(
    settings: AppSettings,
    preferences: AppSettingsRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showPermissionHelp by rememberSaveable { mutableStateOf(false) }
    fun setReminders(enabled: Boolean) {
        showPermissionHelp = false
        scope.launch {
            preferences.setVocabularyReminders(enabled)
            VocabularyReminderWorker.schedule(context, enabled)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setReminders(true) else showPermissionHelp = true
    }
    var newLimit by rememberSaveable { mutableStateOf(settings.vocabularyNewLimit.toString()) }
    var reviewLimit by rememberSaveable { mutableStateOf(settings.vocabularyReviewLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vocabulary_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(stringResource(R.string.vocabulary_auto_save), null, settings.vocabularyAutoSave, {
                    scope.launch { preferences.setVocabularyAutoSave(it) }
                })
                SwitchRow(stringResource(R.string.vocabulary_save_context), null, settings.vocabularySaveContext, {
                    scope.launch { preferences.setVocabularySaveContext(it) }
                })
                SwitchRow(stringResource(R.string.vocabulary_reminders), null, settings.vocabularyReminders, { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        setReminders(enabled)
                    }
                })
                if (showPermissionHelp) {
                    Text(stringResource(R.string.vocabulary_reminder_permission))
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text(stringResource(R.string.translation_open_settings)) }
                }
                Text(stringResource(R.string.vocabulary_unlimited))
                OutlinedTextField(
                    value = newLimit,
                    onValueChange = { newLimit = it.take(4).filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.vocabulary_new_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = reviewLimit,
                    onValueChange = { reviewLimit = it.take(4).filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.vocabulary_review_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newLimit.toIntOrNull() != null && reviewLimit.toIntOrNull() != null,
                onClick = {
                    scope.launch {
                        preferences.setVocabularyLimits(newLimit.toInt(), reviewLimit.toInt())
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
