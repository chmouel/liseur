package com.chmouel.liseur.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chmouel.liseur.data.library.evaluationExport
import com.chmouel.liseur.ui.launchPicker
import com.chmouel.liseur.ui.settings.search.SettingsEntries
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun EvaluationExportButton(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val message = try {
                withContext(Dispatchers.IO) {
                    val contents = evaluationExport(context.applicationContext.container.database.bookEvaluationDao().all())
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open export")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(contents.toString(2)) }
                }
                R.string.evaluation_exported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.evaluation_export_failed
            } finally {
                busy = false
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    TextButton(
        onClick = { launcher.launchPicker(context, "liseur-ratings-and-reviews.json") },
        enabled = enabled && !busy,
        modifier = Modifier.settingsAnchor(SettingsEntries.EvaluationExport),
    ) { Text(stringResource(R.string.evaluation_export)) }
}
