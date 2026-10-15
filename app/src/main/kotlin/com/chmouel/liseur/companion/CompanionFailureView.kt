package com.chmouel.liseur.companion

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import kotlinx.coroutines.launch

/** Failures a different model may well get past. */
private val MODEL_FAILURES = setOf(
    CompanionFailure.MALFORMED,
    CompanionFailure.REFUSED,
    CompanionFailure.LIMIT,
    CompanionFailure.TOO_LONG,
    CompanionFailure.MODEL_MISSING,
    CompanionFailure.SERVER,
)

/**
 * A failed lookup: what happened, what may help, and on request the
 * details, which the reader can select or copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailureMessage(
    failed: CompanionView.Failed,
    onRetry: () -> Unit,
    onChooseModel: () -> Unit,
) {
    val details = failed.details
    var open by rememberSaveable { mutableStateOf(false) }
    Text(failureText(failed.failure), style = MaterialTheme.typography.bodyLarge)
    hintText(failed.failure, details)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (details?.model != null && details.host != null) {
        Text(
            stringResource(R.string.companion_failed_asked, details.model, details.host),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.companion_retry)) }
        if (failed.failure in MODEL_FAILURES && details?.model != null) {
            OutlinedButton(onClick = onChooseModel) { Text(stringResource(R.string.companion_choose_model)) }
        }
        if (details != null && failed.failure != CompanionFailure.CANCELLED) {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.companion_details)) }
        }
    }
    if (open && details != null) FailureDetailsDialog(details) { open = false }
}

@Composable
private fun failureText(failure: CompanionFailure): String = stringResource(
    when (failure) {
        CompanionFailure.CANCELLED -> R.string.companion_failed_cancelled
        CompanionFailure.KEY -> R.string.companion_failed_key
        CompanionFailure.RATE_LIMITED -> R.string.companion_failed_rate_limited
        CompanionFailure.LOCAL_NETWORK -> R.string.companion_failed_local_network
        CompanionFailure.NETWORK -> R.string.companion_failed_network
        CompanionFailure.MODEL_MISSING -> R.string.companion_failed_model_missing
        CompanionFailure.SERVER -> R.string.companion_failed_server
        CompanionFailure.MALFORMED -> R.string.companion_failed_malformed
        CompanionFailure.REFUSED -> R.string.companion_failed_refused
        CompanionFailure.LIMIT -> R.string.companion_failed_limit
        CompanionFailure.TOO_LONG -> R.string.companion_failed_too_long
        CompanionFailure.UNREADABLE -> R.string.companion_failed_unreadable
    },
)

@Composable
private fun hintText(failure: CompanionFailure, details: FailureDetails?): String? = when (failure) {
    CompanionFailure.MALFORMED -> stringResource(R.string.companion_hint_malformed)
    CompanionFailure.REFUSED -> stringResource(R.string.companion_hint_refused)
    CompanionFailure.LIMIT -> stringResource(R.string.companion_hint_limit)
    CompanionFailure.TOO_LONG -> stringResource(R.string.companion_hint_too_long)
    CompanionFailure.SERVER -> details?.status?.let { stringResource(R.string.companion_hint_server, it) }
    else -> null
}

@Composable
private fun ruleText(code: String): String? = when (code) {
    "not_json" -> stringResource(R.string.companion_rule_not_json)
    "actions_after_limit" -> stringResource(R.string.companion_rule_actions_after_limit)
    "unknown_reply" -> stringResource(R.string.companion_rule_unknown_reply)
    "bad_action" -> stringResource(R.string.companion_rule_bad_action)
    "bad_citation" -> stringResource(R.string.companion_rule_bad_citation)
    "empty_profile" -> stringResource(R.string.companion_rule_empty_profile)
    "bad_candidates" -> stringResource(R.string.companion_rule_bad_candidates)
    CompanionPrompt.TOO_LONG -> stringResource(R.string.companion_rule_too_long)
    else -> null
}

/** One labelled fact; [verbatim] text is the program's or the server's own, shown in a fixed-width face. */
private data class DetailRow(val label: String, val value: String, val verbatim: Boolean = false)

@Composable
private fun FailureDetailsDialog(details: FailureDetails, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.translation_copied)
    val rows = buildList {
        details.host?.let { add(DetailRow(stringResource(R.string.companion_details_server), it)) }
        details.model?.let { add(DetailRow(stringResource(R.string.companion_details_model), it)) }
        add(DetailRow(stringResource(R.string.companion_details_error), details.error, verbatim = true))
        details.status?.let { add(DetailRow(stringResource(R.string.companion_details_status), it.toString())) }
        if (details.requests > 0) add(DetailRow(stringResource(R.string.companion_details_requests), details.requests.toString()))
        if (details.rules.isNotEmpty()) {
            val rules = details.rules.map { code -> ruleText(code)?.let { "$it ($code)" } ?: code }
            add(DetailRow(stringResource(R.string.companion_details_rules), rules.joinToString("\n") { "• $it" }))
        }
        details.serverMessage?.let { add(DetailRow(stringResource(R.string.companion_details_server_message), it, verbatim = true)) }
        details.reply?.let { add(DetailRow(stringResource(R.string.companion_details_reply), it, verbatim = true)) }
    }
    val title = stringResource(R.string.companion_details_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEach { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                row.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                row.value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = if (row.verbatim) FontFamily.Monospace else null,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(
                onClick = {
                    val text = rows.joinToString("\n\n") { "${it.label}:\n${it.value}" }
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, text)))
                        // Android 13 confirms a copy itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.companion_details_copy)) }
        },
    )
}
