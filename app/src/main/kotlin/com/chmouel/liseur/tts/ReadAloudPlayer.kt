package com.chmouel.liseur.tts

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.reader.chrome.ChromePill
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The controls laid over the page while its book is read aloud, shown
 * with the rest of the chrome and painted like the other pills in the
 * reading theme, and above them whatever the listener has to be told.
 */
@Composable
internal fun ReadAloudPlayer(
    feature: SpeechReadAloud,
    bookId: String,
    theme: ReaderTheme,
    controls: Boolean,
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
        val service by feature.service.collectAsStateWithLifecycle(null)
        service?.AccessPrompt(theme)
        AnimatedVisibility(
            visible = notice != null,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Text(
                    text = notice?.let {
                        stringResource(
                            it.message(),
                            feature.noticeHost ?: stringResource(feature.noticeLabel),
                        )
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = here != null && controls,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        SpeedButton(feature)
                        SleepButton(feature)
                        VoiceButton(feature)
                    }
                    VoiceStatus(feature)
                }
            }
        }
    }
}

/** Which voice is reading, and in what language, under the controls. */
@Composable
private fun VoiceStatus(feature: SpeechReadAloud) {
    val service by feature.service.collectAsStateWithLifecycle(null)
    // The text, and the same without the flag for a screen reader.
    val (text, spoken) = service?.voiceStatus() ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .semantics { contentDescription = spoken },
    )
}

/** Picks how fast the book is read, heard at once. */
@Composable
private fun SpeedButton(feature: SpeechReadAloud) {
    val scope = rememberCoroutineScope()
    val speed by feature.speed.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val description = stringResource(R.string.read_aloud_speed)
    var open by remember { mutableStateOf(false) }
    Box {
        TextControlButton(description, { open = true }) {
            Text(
                stringResource(R.string.read_aloud_speed_value, ReadAloudSpeed.number(speed, locale)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReadAloudSpeed.STEPS.forEach { step ->
                MenuItem(
                    stringResource(R.string.read_aloud_speed_value, ReadAloudSpeed.number(step, locale)),
                    step == speed,
                ) {
                    open = false
                    scope.launch { feature.setSpeed(step) }
                }
            }
        }
    }
}

/** Sets when reading aloud pauses by itself, showing the minutes left once set. */
@Composable
private fun SleepButton(feature: SpeechReadAloud) {
    val timer by feature.sleepTimer.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timer) {
        while (timer is SleepTimer.Timed) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextControlButton(stringResource(R.string.read_aloud_sleep_timer), { open = true }) {
            when (val set = timer) {
                null -> Icon(Icons.Outlined.Bedtime, contentDescription = null)
                SleepTimer.EndOfChapter -> Text(
                    stringResource(R.string.read_aloud_sleep_chapter_end),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                is SleepTimer.Timed -> Text(
                    stringResource(R.string.read_aloud_sleep_left, set.minutesLeft(now)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(stringResource(R.string.read_aloud_sleep_off), timer == null) {
                open = false
                feature.setSleepTimer(null)
            }
            MenuItem(stringResource(R.string.read_aloud_sleep_end_of_chapter), timer == SleepTimer.EndOfChapter) {
                open = false
                feature.setSleepTimerToChapterEnd()
            }
            SleepTimer.CHOICES.forEach { minutes ->
                MenuItem(pluralStringResource(R.plurals.read_aloud_sleep_minutes, minutes, minutes), (timer as? SleepTimer.Timed)?.minutes == minutes) {
                    open = false
                    feature.setSleepTimer(minutes)
                }
            }
        }
    }
}

/**
 * Picks the voice the book is read in, from the chosen service's. The
 * book reads on in it from the start of the sentence.
 */
@Composable
private fun VoiceButton(feature: SpeechReadAloud) {
    val service by feature.service.collectAsStateWithLifecycle(null)
    var open by remember { mutableStateOf(false) }
    Box {
        ControlButton({ open = true }) {
            Icon(Icons.Outlined.RecordVoiceOver, stringResource(R.string.read_aloud_change_voice))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            service?.VoiceMenuItems(onPicked = { open = false })
        }
    }
}

@Composable
internal fun MenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
    )
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

/** A control showing text, which an icon-sized button would clip. */
@Composable
private fun TextControlButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp),
    ) {
        content()
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
    ReadAloudNotice.TermsRequired -> R.string.read_aloud_notice_terms_required
    ReadAloudNotice.NotSetUp -> R.string.read_aloud_notice_not_set_up
    ReadAloudNotice.SelectionNotFound -> R.string.read_aloud_notice_selection_not_found
    ReadAloudNotice.Unavailable -> R.string.read_aloud_notice_unavailable
}

private const val NOTICE_MS = 5_000L
