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
): Boolean = startPageTranslation(open, SentenceTranslator::close, prepare, wanted, install)

/**
 * As above for anything held open, such as [SentenceTranslations] shared
 * with read aloud: [close] lets go of what [open] gave unless installed.
 */
internal suspend fun <T : Any, P> startPageTranslation(
    open: suspend () -> T?,
    close: (T) -> Unit,
    prepare: suspend () -> P,
    wanted: () -> Boolean,
    install: (T, P) -> Unit,
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
        if (!installed) close(translator)
    }
}
