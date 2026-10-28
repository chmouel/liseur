package com.chmouel.liseur.reader.annotations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.chmouel.liseur.domain.DictionaryUrl

/** Hands a passage to whatever the reader uses to share things. */
fun Context.shareText(text: String, subject: String?) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    startActivity(Intent.createChooser(send, null))
}

/**
 * Hands a word to a dictionary app.
 *
 * `PROCESS_TEXT` is what dictionary apps register for, so this works with
 * whatever the reader already has installed — no dictionary is bundled, and
 * nothing proprietary is required. It also needs no network, which is why it
 * stays available when the online lookup is switched off. When nothing
 * handles it, fall back to the dictionary site in the browser.
 */
fun Context.lookUpExternally(text: String, dictionaryBaseUrl: String) {
    val word = text.trim().takeIf { it.isNotBlank() } ?: return
    val browserWord = browserLookupTerm(word)
    val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    // Ask first so devices without a handler fall back to the browser.
    if (packageManager.queryIntentActivities(process, 0).isEmpty()) {
        openDictionaryEntry(browserWord, dictionaryBaseUrl)
        return
    }
    try {
        startActivity(process)
    } catch (_: ActivityNotFoundException) {
        openDictionaryEntry(browserWord, dictionaryBaseUrl)
    }
}

/**
 * What the browser fallback asks the dictionary site for. A short phrase on
 * one line can be an entry of its own ("je ne sais quoi", #388); a longer
 * passage cannot, so it gets its first word.
 */
internal fun browserLookupTerm(text: String): String {
    val words = text.trim().split(Regex("\\s+"))
    val oneLine = text.trim().none { it == '\n' || it == '\r' }
    return if (oneLine && words.size <= MAX_PHRASE_WORDS) words.joinToString(" ") else words.first()
}

private const val MAX_PHRASE_WORDS = 5

/** Opens the word's full entry on the configured dictionary site. */
fun Context.openDictionaryEntry(word: String, dictionaryBaseUrl: String) {
    val term = word.trim().takeIf { it.isNotBlank() } ?: return
    val url: Uri = DictionaryUrl.entryPage(dictionaryBaseUrl, term).toUri()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
}

internal fun citationText(text: String, title: String?, authors: List<String>): String {
    val source = listOfNotNull(
        title?.trim()?.takeIf { it.isNotEmpty() },
        authors.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() },
    ).joinToString(" — ")
    return if (source.isEmpty()) text else "$text\n\n$source"
}

internal fun processTextIntent(text: String = "") = Intent(Intent.ACTION_PROCESS_TEXT).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
}

internal fun webSearchIntent(text: String = "") = Intent(Intent.ACTION_WEB_SEARCH).apply {
    putExtra(android.app.SearchManager.QUERY, text)
}

internal fun Context.canHandleSelectionIntent(intent: Intent): Boolean =
    packageManager.queryIntentActivities(intent, 0).isNotEmpty()

internal fun Context.launchSelectionIntent(intent: Intent, chooser: Boolean = false) {
    try {
        if (!canHandleSelectionIntent(intent)) throw ActivityNotFoundException()
        startActivity(if (chooser) Intent.createChooser(intent, null) else intent)
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(this, com.chmouel.liseur.R.string.selection_no_handler, android.widget.Toast.LENGTH_SHORT).show()
    }
}

internal fun Context.copySelection(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(this, com.chmouel.liseur.R.string.selection_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}
