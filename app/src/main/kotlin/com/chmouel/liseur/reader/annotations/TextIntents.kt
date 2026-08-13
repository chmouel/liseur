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
    val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    // Ask first so devices without a handler fall back to the browser.
    if (packageManager.queryIntentActivities(process, 0).isEmpty()) {
        openDictionaryEntry(word, dictionaryBaseUrl)
        return
    }
    try {
        startActivity(process)
    } catch (_: ActivityNotFoundException) {
        openDictionaryEntry(word, dictionaryBaseUrl)
    }
}

/** Opens the word's full entry on the configured dictionary site. */
fun Context.openDictionaryEntry(word: String, dictionaryBaseUrl: String) {
    val term = word.substringBefore(' ').takeIf { it.isNotBlank() } ?: return
    val url: Uri = DictionaryUrl.entryPage(dictionaryBaseUrl, term).toUri()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
}
