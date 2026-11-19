package com.chmouel.liseur.reader.annotations

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_SMART_SELECTION_CHARS = 120
const val MIN_ENTITY_CONFIDENCE = 0.7f

enum class SmartEntityKind {
    URL,
    ADDRESS,
    EMAIL,
    PHONE,
    DATETIME,
    FLIGHT,
    OTHER,
}

data class SmartSelectionAction(
    /** The selected text this action was classified from, trimmed. */
    val text: String,
    val title: String,
    val contentDescription: String,
    val entityKind: SmartEntityKind,
    val onInvoke: () -> Unit,
)

fun isEligibleForSmartClassification(text: String?): Boolean {
    val trimmed = text?.trim() ?: return false
    if (trimmed.length !in 2..MAX_SMART_SELECTION_CHARS) return false
    var lines = 1
    var index = 0
    while (index < trimmed.length) {
        val ch = trimmed[index]
        if (ch == '\r' || ch == '\n') {
            lines++
            if (lines > 2) return false
            if (ch == '\r' && index + 1 < trimmed.length && trimmed[index + 1] == '\n') {
                index++
            }
        }
        index++
    }
    return true
}

fun mapEntityTypeToKind(entityType: String): SmartEntityKind =
    when (entityType.trim().lowercase(Locale.ROOT)) {
        "url" -> SmartEntityKind.URL
        "address" -> SmartEntityKind.ADDRESS
        "email" -> SmartEntityKind.EMAIL
        "phone" -> SmartEntityKind.PHONE
        "date", "datetime" -> SmartEntityKind.DATETIME
        "flight" -> SmartEntityKind.FLIGHT
        else -> SmartEntityKind.OTHER
    }

fun selectPrimaryEntity(
    scores: Map<String, Float>,
    minConfidence: Float = MIN_ENTITY_CONFIDENCE,
): SmartEntityKind? =
    scores.entries
        .filter { (_, score) -> !score.isNaN() && score >= minConfidence }
        .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.let { mapEntityTypeToKind(it.key) }

suspend fun classifySelectionText(
    context: Context,
    text: String,
    languageTags: String? = null,
): List<SmartSelectionAction> {
    if (!isEligibleForSmartClassification(text)) return emptyList()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
    val trimmed = text.trim()
    return withContext(Dispatchers.IO) {
        runCatching {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
                ?: return@runCatching emptyList()
            val requestBuilder = TextClassification.Request.Builder(trimmed, 0, trimmed.length)
            val tags = languageTags?.trim()?.takeIf { it.isNotEmpty() }
            if (tags != null) {
                requestBuilder.setDefaultLocales(LocaleList.forLanguageTags(tags))
            }
            val classification = classifier.classifyText(requestBuilder.build())
            val scores = buildMap(classification.entityCount) {
                for (i in 0 until classification.entityCount) {
                    val entity = classification.getEntity(i)
                    put(entity, classification.getConfidenceScore(entity))
                }
            }
            val entityKind = selectPrimaryEntity(scores) ?: SmartEntityKind.OTHER
            classification.actions
                .asSequence()
                .filter { action -> action.shouldShowIcon() || action.title.isNotBlank() }
                .take(2)
                .map { action ->
                    val title = action.title.toString().trim()
                    val description = action.contentDescription.toString().trim().ifEmpty { title }
                    val pendingIntent = action.actionIntent
                    SmartSelectionAction(
                        text = trimmed,
                        title = title,
                        contentDescription = description,
                        entityKind = entityKind,
                        onInvoke = { runCatching { pendingIntent.send() } },
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }
}

@Composable
fun rememberSmartSelectionActions(
    text: String?,
    languageTags: String? = null,
    onActionInvoked: () -> Unit,
): List<SmartSelectionAction> {
    val context = LocalContext.current
    val currentOnActionInvoked by rememberUpdatedState(onActionInvoked)
    // A fresh holder per input, so the last selection's actions are gone
    // the moment the selection changes rather than once the new one is
    // classified.
    val holder = remember(text, languageTags) { mutableStateOf(emptyList<SmartSelectionAction>()) }
    LaunchedEffect(holder) {
        val candidate = text
        if (candidate != null && isEligibleForSmartClassification(candidate)) {
            holder.value = classifySelectionText(context, candidate, languageTags)
        }
    }
    val rawActions by holder
    return remember(rawActions) {
        rawActions.map { action ->
            action.copy(
                onInvoke = {
                    action.onInvoke()
                    currentOnActionInvoked()
                },
            )
        }
    }
}
