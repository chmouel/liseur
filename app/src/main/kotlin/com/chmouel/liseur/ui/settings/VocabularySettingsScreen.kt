package com.chmouel.liseur.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.search.SettingsEntries
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.vocabulary.VocabularyReminderWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabularySettingsScreen(
    settings: AppSettings,
    preferences: AppSettingsRepository,
    onBack: () -> Unit,
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
    var newLimit by rememberSaveable(settings.vocabularyNewLimit) { mutableStateOf(settings.vocabularyNewLimit.toString()) }
    var reviewLimit by rememberSaveable(settings.vocabularyReviewLimit) { mutableStateOf(settings.vocabularyReviewLimit.toString()) }
    val newValue = newLimit.toIntOrNull()
    val reviewValue = reviewLimit.toIntOrNull()
    val limitsChanged = newValue != settings.vocabularyNewLimit || reviewValue != settings.vocabularyReviewLimit
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = stringResource(R.string.vocabulary_title)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = { PaneBackButton(onBack) },
                actions = {
                    InfoButton(
                        title = title,
                        body = stringResource(R.string.vocabulary_help, stringResource(R.string.vocabulary_export_csv)),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                SettingsGroup(stringResource(R.string.settings_dictionary)) {
                    SwitchRow(
                        title = stringResource(R.string.vocabulary_auto_save),
                        subtitle = stringResource(R.string.vocabulary_auto_save_detail),
                        checked = settings.vocabularyAutoSave,
                        onCheckedChange = { scope.launch { preferences.setVocabularyAutoSave(it) } },
                        modifier = Modifier.settingsAnchor(SettingsEntries.VocabularyAutoSave),
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.vocabulary_save_context),
                        subtitle = null,
                        checked = settings.vocabularySaveContext,
                        onCheckedChange = { scope.launch { preferences.setVocabularySaveContext(it) } },
                        modifier = Modifier.settingsAnchor(SettingsEntries.VocabularyContext),
                    )
                }

                SettingsGroup(stringResource(R.string.vocabulary_review)) {
                    SwitchRow(
                        title = stringResource(R.string.vocabulary_reminders),
                        subtitle = null,
                        checked = settings.vocabularyReminders,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                setReminders(enabled)
                            }
                        },
                        modifier = Modifier.settingsAnchor(SettingsEntries.VocabularyReminders),
                    )
                    if (showPermissionHelp) {
                        Text(
                            stringResource(R.string.vocabulary_reminder_permission),
                            Modifier.padding(horizontal = 16.dp),
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }) { Text(stringResource(R.string.translation_open_settings)) }
                    }
                    RowDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vocabulary_unlimited))
                        OutlinedTextField(
                            value = newLimit,
                            onValueChange = { newLimit = it.take(4).filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.vocabulary_new_limit)) },
                            modifier = Modifier.fillMaxWidth().settingsAnchor(SettingsEntries.VocabularyNewLimit),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = reviewLimit,
                            onValueChange = { reviewLimit = it.take(4).filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.vocabulary_review_limit)) },
                            modifier = Modifier.fillMaxWidth().settingsAnchor(SettingsEntries.VocabularyReviewLimit),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                if (newValue != null && reviewValue != null) {
                                    scope.launch { preferences.setVocabularyLimits(newValue, reviewValue) }
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            enabled = limitsChanged && newValue != null && reviewValue != null,
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}
