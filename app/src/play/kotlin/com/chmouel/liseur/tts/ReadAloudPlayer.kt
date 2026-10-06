package com.chmouel.liseur.tts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.reader.chrome.ChromePill
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.delay

/**
 * The controls laid over the page while its book is read aloud, painted
 * like the other pills in the reading theme, and above them whatever the
 * listener has to be told.
 */
@Composable
internal fun ReadAloudPlayer(
    feature: SpeechReadAloud,
    bookId: String,
    theme: ReaderTheme,
    modifier: Modifier,
) {
    val eInk = LocalEInk.current
    val session by feature.session.collectAsStateWithLifecycle()
    val here = session?.takeIf { it.bookId == bookId }
    var notice by remember { mutableStateOf<ReadAloudNotice?>(null) }
    // A book still playing in the background speaks only in its own reader.
    LaunchedEffect(feature, bookId) {
        feature.notices.collect { if (it.bookId == bookId) notice = it.notice }
    }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = notice != null,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Text(
                    text = notice?.let { stringResource(it.message(), stringResource(feature.noticeProvider.label)) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = here != null,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    ControlButton(feature::skipBackward) {
                        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.read_aloud_previous_sentence))
                    }
                    when {
                        // Nothing heard yet: the first sentence is still on its way.
                        here?.utterance == null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            BusyIndicator(
                                modifier = Modifier.size(24.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                        }
                        here?.playing == true -> ControlButton(feature::pause) {
                            Icon(Icons.Filled.Pause, stringResource(R.string.read_aloud_pause))
                        }
                        else -> ControlButton(feature::resume) {
                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.read_aloud_resume))
                        }
                    }
                    ControlButton(feature::skipForward) {
                        Icon(Icons.Filled.SkipNext, stringResource(R.string.read_aloud_next_sentence))
                    }
                    ControlButton(feature::stop) {
                        Icon(Icons.Filled.Close, stringResource(R.string.read_aloud_stop))
                    }
                }
            }
        }
    }
}

/** The selection bar's way into reading aloud. */
@Composable
internal fun ReadAloudSelectionButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = stringResource(R.string.read_aloud_from_here),
        )
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalContentColor.current),
        content = content,
    )
}

private fun ReadAloudNotice.message(): Int = when (this) {
    ReadAloudNotice.Network -> R.string.read_aloud_notice_network
    ReadAloudNotice.RateLimited -> R.string.read_aloud_notice_rate_limited
    ReadAloudNotice.Service -> R.string.read_aloud_notice_service
    ReadAloudNotice.Output -> R.string.read_aloud_notice_output
    ReadAloudNotice.InvalidKey -> R.string.read_aloud_notice_invalid_key
    ReadAloudNotice.InvalidVoice -> R.string.read_aloud_notice_invalid_voice
    ReadAloudNotice.NotSetUp -> R.string.read_aloud_notice_not_set_up
    ReadAloudNotice.SelectionNotFound -> R.string.read_aloud_notice_selection_not_found
    ReadAloudNotice.Unavailable -> R.string.read_aloud_notice_unavailable
}

private const val NOTICE_MS = 5_000L
