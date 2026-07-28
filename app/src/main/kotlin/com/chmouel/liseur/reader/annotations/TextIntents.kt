package com.chmouel.liseur.reader.annotations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

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

/** Looks a passage up on the web, the way the browser's own search does. */
fun Context.webSearch(text: String) {
    if (text.isBlank()) return
    val search = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra("query", text.trim())
    runCatching { startActivity(search) }
}

/**
 * Asks another app to define a word.
 *
 * `PROCESS_TEXT` is what dictionary apps register for, so this works with
 * whatever the reader already has installed — no dictionary is bundled, and
 * nothing proprietary is required. When nothing handles it, fall back to
 * Wiktionary in the browser, which is free content and needs no account.
 */
fun Context.lookUp(text: String) {
    val word = text.trim().takeIf { it.isNotBlank() } ?: return
    val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    try {
        startActivity(Intent.createChooser(process, null))
    } catch (_: ActivityNotFoundException) {
        openWiktionary(word)
    }
}

private fun Context.openWiktionary(word: String) {
    val url: Uri = "https://en.wiktionary.org/wiki/${Uri.encode(word.substringBefore(' '))}"
        .toUri()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
}
