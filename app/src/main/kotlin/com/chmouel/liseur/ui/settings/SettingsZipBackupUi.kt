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
import com.chmouel.liseur.container
import com.chmouel.liseur.data.library.SettingsBackupExportResult
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.data.library.SettingsBackupInspection
import com.chmouel.liseur.data.library.SettingsBackupRestoreResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsZipBackupUi(
    val preview: SettingsBackupInspection.Ready?,
    val status: SettingsBackupUiStatus?,
    val pendingUri: Uri?,
    val export: () -> Unit,
    val restore: () -> Unit,
    val confirmRestore: () -> Unit,
    val dismissPreview: () -> Unit,
)

sealed interface SettingsBackupUiStatus {
    data class Exported(val fonts: Int) : SettingsBackupUiStatus
    data class Restored(
        val fontsImported: Int,
        val fontsAlreadyPresent: Int,
        val fontFailures: Int,
    ) : SettingsBackupUiStatus

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupUiStatus
    data object PartiallyRestored : SettingsBackupUiStatus
}

@Composable
fun rememberSettingsZipBackup(): SettingsZipBackupUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.settingsBackup }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<SettingsBackupInspection.Ready?>(null) }
    var status by remember { mutableStateOf<SettingsBackupUiStatus?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            scope.launch {
                status = when (val result = repository.exportTo(it)) {
                    is SettingsBackupExportResult.Exported ->
                        SettingsBackupUiStatus.Exported(result.fonts)
                    is SettingsBackupExportResult.Failed ->
                        SettingsBackupUiStatus.Failed(result.failure)
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
                    is SettingsBackupInspection.Failed ->
                        status = SettingsBackupUiStatus.Failed(result.failure)
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
                        is SettingsBackupRestoreResult.Restored -> SettingsBackupUiStatus.Restored(
                            result.fontsImported,
                            result.fontsAlreadyPresent,
                            result.fontFailures,
                        )
                        is SettingsBackupRestoreResult.Failed ->
                            SettingsBackupUiStatus.Failed(result.failure)
                        SettingsBackupRestoreResult.PartiallyRestored ->
                            SettingsBackupUiStatus.PartiallyRestored
                    }
                }
            }
        },
        dismissPreview = { pendingUri = null; preview = null },
    )
}
