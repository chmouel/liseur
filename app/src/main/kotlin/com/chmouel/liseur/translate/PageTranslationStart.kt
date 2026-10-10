package com.chmouel.liseur.translate

/**
 * Opens a translator and hands it to [install] once [prepare] has found
 * where to begin and the start is still [wanted]. Until then the
 * translator is this function's, and it is closed on every way out,
 * cancellation included. True once installed.
 */
internal suspend fun <P> startPageTranslation(
    open: suspend () -> SentenceTranslator?,
    prepare: suspend () -> P,
    wanted: () -> Boolean,
    install: (SentenceTranslator, P) -> Unit,
): Boolean {
    val translator = open() ?: return false
    var installed = false
    try {
        val start = prepare()
        if (!wanted()) return false
        install(translator, start)
        installed = true
        return true
    } finally {
        if (!installed) translator.close()
    }
}
