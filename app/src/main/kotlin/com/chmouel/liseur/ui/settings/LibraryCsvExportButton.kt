package com.chmouel.liseur.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.library.goodreadsCsvExport
import com.chmouel.liseur.ui.launchPicker
import com.chmouel.liseur.ui.settings.search.SettingsEntries
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun LibraryCsvExportButton(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val message = try {
                withContext(Dispatchers.IO) {
                    val db = context.applicationContext.container.database
                    val csv = goodreadsCsvExport(
                        books = db.bookDao().allOnce(),
                        progress = db.readingProgressDao().getAll(),
                        evaluations = db.bookEvaluationDao().all(),
                        metadata = db.bookMetadataDao().all(),
                    )
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open export")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                }
                R.string.library_csv_exported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.library_csv_export_failed
            } finally {
                busy = false
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    BackupActionRow(
        icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
        title = stringResource(R.string.library_csv_export),
        subtitle = stringResource(R.string.library_csv_export_detail),
        onClick = { launcher.launchPicker(context, "liseur-library.csv") },
        enabled = enabled && !busy,
        modifier = Modifier.settingsAnchor(SettingsEntries.LibraryCsvExport),
    )
}
