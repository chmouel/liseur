package com.chmouel.liseur.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.library.SettingsBackupInspection
import com.chmouel.liseur.data.library.SettingsBackupResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsZipBackupUi(
    val preview: SettingsBackupInspection.Ready?,
    val status: String?,
    val pendingUri: Uri?,
    val export: () -> Unit,
    val restore: () -> Unit,
    val confirmRestore: () -> Unit,
    val dismissPreview: () -> Unit,
)

@Composable
fun rememberSettingsZipBackup(): SettingsZipBackupUi {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.settingsBackup }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<SettingsBackupInspection.Ready?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            scope.launch {
                status = when (val result = repository.exportTo(it)) {
                    is SettingsBackupResult.Exported -> app.getString(R.string.settings_backup_exported, result.fonts)
                    is SettingsBackupResult.Restored -> ""
                    is SettingsBackupResult.Failed -> app.getString(R.string.settings_backup_failed, result.reason)
                }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                when (val result = repository.inspect(it)) {
                    is SettingsBackupInspection.Ready -> {
                        pendingUri = it
                        preview = result
                    }
                    is SettingsBackupInspection.Failed -> status = app.getString(R.string.settings_backup_failed, result.reason)
                }
            }
        }
    }
    return SettingsZipBackupUi(
        preview = preview,
        status = status,
        pendingUri = pendingUri,
        export = {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            save.launch("liseur-backup-$date.zip")
        },
        restore = { open.launch(arrayOf("*/*")) },
        confirmRestore = {
            val uri = pendingUri
            if (uri != null) {
                pendingUri = null
                preview = null
                scope.launch {
                    status = when (val result = repository.restore(uri)) {
                        is SettingsBackupResult.Restored -> app.getString(
                            R.string.settings_backup_restored,
                            result.fontsImported,
                            result.fontsAlreadyPresent,
                            result.fontFailures,
                        )
                        is SettingsBackupResult.Failed -> app.getString(R.string.settings_backup_failed, result.reason)
                        is SettingsBackupResult.Exported -> ""
                    }
                }
            }
        },
        dismissPreview = { pendingUri = null; preview = null },
    )
}
