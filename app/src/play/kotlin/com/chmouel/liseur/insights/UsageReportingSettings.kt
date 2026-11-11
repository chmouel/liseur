package com.chmouel.liseur.insights

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.LiseurApplication
import com.chmouel.liseur.R
import com.chmouel.liseur.providers.PlaySettingsEntries
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SwitchRow
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import kotlinx.coroutines.launch

@Composable
fun UsageReportingSettings() {
    val reporting = (LocalContext.current.applicationContext as LiseurApplication).container.usageReporting
    val enabled by reporting.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    RowDivider()
    Column {
        SwitchRow(
            title = stringResource(R.string.reporting_title),
            subtitle = stringResource(R.string.reporting_detail),
            checked = enabled,
            enabled = !saving,
            onCheckedChange = { choice ->
                saving = true
                scope.launch {
                    try {
                        reporting.setEnabled(choice)
                        failed = false
                    } catch (_: java.io.IOException) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            },
            modifier = Modifier.settingsAnchor(PlaySettingsEntries.Reporting),
        )
        if (failed) Text(stringResource(R.string.reporting_save_failed))
        TextButton(onClick = { uri.openUri("https://chmouel.github.io/liseur/PRIVACY.html") }) {
            Text(stringResource(R.string.reporting_privacy))
        }
    }
}
