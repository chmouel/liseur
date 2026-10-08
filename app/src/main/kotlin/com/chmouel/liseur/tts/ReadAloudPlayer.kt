package com.chmouel.liseur.tts

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.chrome.ChromeCard
import com.chmouel.liseur.reader.chrome.ChromePill
import com.chmouel.liseur.ui.LocalEInk
import java.util.Locale
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
    val pending by feature.pendingChoice.collectAsStateWithLifecycle()
    val asking = pending?.takeIf { it.bookId == bookId }
    var choosing by remember { mutableStateOf(false) }
    // Asked before the book is read, or opened from the controls of the one being read.
    if (asking != null || (choosing && here != null)) {
        key(asking) { ReadAloudVoiceSheet(feature, bookId, asking, onDismiss = { choosing = false }) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val service by feature.service.collectAsStateWithLifecycle(null)
        val waitShown = rememberWaitShown(here?.preparing != false)
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
        // With the controls hidden, the wait for the voice still shows.
        AnimatedVisibility(
            visible = here != null && !controls && waitShown && notice == null,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                ) {
                    VoiceWait(color = LocalContentColor.current, barWidth = 2.5.dp, height = 16.dp)
                    Text(
                        text = stringResource(R.string.read_aloud_preparing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = here != null && controls,
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromeCard(theme = theme) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    ) {
                        VoiceChip(feature, bookId, Modifier.weight(1f, fill = false)) { choosing = true }
                        ControlButton(feature::stop) {
                            Icon(Icons.Filled.Close, stringResource(R.string.read_aloud_stop))
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        SpeedButton(feature)
                        ControlButton(feature::skipBackward) {
                            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.read_aloud_previous_sentence))
                        }
                        PlayButton(feature, here, waitShown, theme)
                        ControlButton(feature::skipForward) {
                            Icon(Icons.Filled.SkipNext, stringResource(R.string.read_aloud_next_sentence))
                        }
                        SleepButton(feature)
                    }
                }
            }
        }
    }
}

/**
 * Play, pause, or the wait for the voice, filled in the page's ink so the
 * one control that matters most is found without looking. While audio is
 * on its way the voice mark takes the icon's place; once playing, the
 * button still pauses.
 */
@Composable
private fun PlayButton(feature: SpeechReadAloud, here: ReadAloudUi?, waitShown: Boolean, theme: ReaderTheme) {
    val colors = IconButtonDefaults.filledIconButtonColors(
        containerColor = theme.foreground,
        contentColor = theme.background,
    )
    val eInk = LocalEInk.current
    val preparing = stringResource(R.string.read_aloud_preparing)
    when {
        // Nothing heard yet: the first sentence is still on its way.
        here == null || (here.preparing && !here.playing) -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(PLAY_SIZE)
                .clip(CircleShape)
                .background(theme.foreground)
                .semantics {
                    contentDescription = preparing
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
        ) {
            AnimatedVisibility(
                visible = waitShown,
                enter = if (eInk) EnterTransition.None else fadeIn(),
                exit = ExitTransition.None,
            ) {
                VoiceWait(color = theme.background)
            }
        }
        here.playing -> {
            val pause = stringResource(R.string.read_aloud_pause)
            FilledIconButton(
                feature::pause,
                Modifier.size(PLAY_SIZE).semantics {
                    contentDescription = pause
                    if (waitShown) stateDescription = preparing
                },
                colors = colors,
            ) {
                Crossfade(waitShown, animationSpec = if (eInk) snap() else tween(), label = "play-wait") { waiting ->
                    if (waiting) {
                        VoiceWait(color = theme.background)
                    } else {
                        Icon(Icons.Filled.Pause, null, Modifier.size(32.dp))
                    }
                }
            }
        }
        else -> FilledIconButton(feature::resume, Modifier.size(PLAY_SIZE), colors = colors) {
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.read_aloud_resume), Modifier.size(32.dp))
        }
    }
}

/**
 * Whether to show the wait for the voice: only once it has lasted, so a
 * quick voice never flashes it, and kept a moment after, so the hand-off
 * from one wait to the next does not blink.
 */
@Composable
private fun rememberWaitShown(preparing: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(preparing) {
        delay(if (preparing) WAIT_SHOW_MS else WAIT_HIDE_MS)
        shown = preparing
    }
    return shown
}

/**
 * Which voice is reading, and in what language: the session's, whatever
 * the settings now hold. A tap opens the voice sheet to change either.
 */
@Composable
private fun VoiceChip(feature: SpeechReadAloud, bookId: String, modifier: Modifier, onClick: () -> Unit) {
    val service by feature.service.collectAsStateWithLifecycle(null)
    val choice by remember(feature, bookId) { feature.sessionChoice(bookId) }.collectAsStateWithLifecycle(null)
    val locale = LocalConfiguration.current.locales[0]
    val name = choice?.let { service?.voiceLabel(it.voice) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.read_aloud_change_voice),
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
    ) {
        val reading = choice
        if (name == null || reading == null) {
            Icon(Icons.Outlined.RecordVoiceOver, stringResource(R.string.read_aloud_change_voice))
            return@Row
        }
        Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(20.dp))
        // The text, and the same without the flag for a screen reader.
        val text = "$name · ${VoiceLabel.languageLabel(reading.language, locale)}"
        val spoken = "$name · ${Locale.forLanguageTag(reading.language).getDisplayName(locale)}"
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = spoken },
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SLEEP_LABEL_MAX),
                )
                is SleepTimer.Timed -> Text(
                    stringResource(R.string.read_aloud_sleep_left, set.minutesLeft(now)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SLEEP_LABEL_MAX),
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

private const val WAIT_SHOW_MS = 400L

private const val WAIT_HIDE_MS = 150L

private val PLAY_SIZE = 56.dp

// Long enough for "12 min", short enough that a translated "end of chapter" leaves the row its five controls.
private val SLEEP_LABEL_MAX = 72.dp
