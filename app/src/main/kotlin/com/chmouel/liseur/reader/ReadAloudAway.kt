package com.chmouel.liseur.reader

/**
 * Whether the reader is away from the voice of a session in this book: the
 * page follows while it plays, so only a move the reader made stands, while
 * a paused session follows nothing and is told by whether the sentence is
 * on the page in front ([pausedShowing], null until the page has answered).
 */
internal fun readAloudAway(
    voice: Boolean,
    playing: Boolean,
    followDetached: Boolean,
    pausedShowing: Boolean?,
): Boolean = when {
    !voice -> false
    playing -> followDetached
    else -> pausedShowing == false
}
