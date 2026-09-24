package com.chmouel.liseur.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.theme.LiseurTheme
import kotlinx.coroutines.launch

/**
 * Picks the span a stats widget covers: when the widget is placed, and
 * again from the launcher's Reconfigure on Android 12 and later.
 *
 * Exported because the launcher starts it, so any app could too. It only
 * acts on a widget id that belongs to one of Liseur's own stats widgets.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Until something is chosen, backing out means no: on the first
        // configuration the launcher then drops the widget it was placing.
        setResult(RESULT_CANCELED, result(appWidgetId))
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        val widget = statsWidgetFor(packageName, provider)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || widget == null) {
            finish()
            return
        }
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        lifecycleScope.launch {
            val current = storedPeriod(this@WidgetConfigActivity, glanceId)
            setContent {
                LiseurTheme {
                    PeriodDialog(
                        initial = current,
                        onPick = { save(appWidgetId, glanceId, widget, it) },
                        onCancel = ::finish,
                    )
                }
            }
        }
    }

    private fun save(appWidgetId: Int, glanceId: GlanceId, widget: GlanceAppWidget, period: WidgetPeriod) {
        lifecycleScope.launch {
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                prefs[WidgetPeriodKey] = period.id
            }
            widget.update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, result(appWidgetId))
            finish()
        }
    }

    private fun result(appWidgetId: Int) =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/** The stats widget behind [provider], or null when it is not one of Liseur's. */
internal fun statsWidgetFor(packageName: String, provider: ComponentName?): GlanceAppWidget? {
    if (provider == null || provider.packageName != packageName) return null
    return when (provider.className) {
        WeekStatsWidgetReceiver::class.java.name -> WeekStatsWidget()
        CoverStatsWidgetReceiver::class.java.name -> CoverStatsWidget()
        else -> null
    }
}

@Composable
private fun PeriodDialog(
    initial: WidgetPeriod,
    onPick: (WidgetPeriod) -> Unit,
    onCancel: () -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.widget_config_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (period in WidgetPeriod.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = period == chosen,
                                onClick = { chosen = period },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = period == chosen, onClick = null)
                        Text(
                            text = stringResource(period.headingRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(chosen) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
