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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.MyLocation
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
import com.chmouel.liseur.readaloud.ReadAloudProblem
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.chrome.ChromeCard
import com.chmouel.liseur.reader.chrome.ChromePill
import com.chmouel.liseur.ui.LocalEInk
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

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
    away: Boolean,
    onReturnToVoice: () -> Unit,
    onReadFromHere: (() -> Unit)?,
    modifier: Modifier,
    start: (Locator, SpeechTranslation?) -> Unit,
) {
    val eInk = LocalEInk.current
    val session by feature.session.collectAsStateWithLifecycle()
    val here = session?.takeIf { it.bookId == bookId }
    val problem by feature.problem.collectAsStateWithLifecycle()
    val shown = problem?.takeIf { it.bookId == bookId }
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
    val startingBook by feature.starting.collectAsStateWithLifecycle()
    // From the tap, before the session exists: the voice is already on its way.
    val starting = startingBook == bookId && here == null && asking == null
    var choosing by remember { mutableStateOf(false) }
    // Opened from a problem, with no session to read on in: it saves the
    // voice or the settings and goes, and reading starts again from the menu.
    var alone by remember { mutableStateOf(false) }
    var settingsFirst by remember { mutableStateOf(false) }
    val openVoice = { standalone: Boolean, settingsAtOnce: Boolean ->
        alone = standalone
        settingsFirst = settingsAtOnce
        choosing = true
    }
    LaunchedEffect(asking) { if (asking != null) choosing = true }
    // Asked before the book is read, or opened from the controls of the one
    // being read. It stays when changing the service in its settings ends
    // the session, until it is dismissed.
    if (choosing || asking != null) {
        ReadAloudVoiceSheet(
            feature,
            bookId,
            asking,
            start,
            onDismiss = {
                choosing = false
                alone = false
                settingsFirst = false
            },
            standalone = alone,
            settingsFirst = settingsFirst,
        )
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val service by feature.service.collectAsStateWithLifecycle(null)
        val waitShown = rememberWaitShown(starting || here?.preparing == true)
        service?.AccessPrompt(theme)
        // A problem stays until it is answered, whether the chrome is up or not.
        shown?.let { current ->
            ProblemCard(
                problem = current,
                theme = theme,
                host = feature.noticeHost ?: stringResource(feature.noticeLabel),
                canRetry = here != null,
                onAction = { action ->
                    when (action) {
                        ProblemAction.Retry -> feature.resume()
                        ProblemAction.Skip -> feature.skipForward()
                        ProblemAction.ChooseVoice -> openVoice(true, false)
                        // The sheet's own not-set-up state offers the settings from there.
                        ProblemAction.Settings -> openVoice(true, current.notice != ReadAloudNotice.NotSetUp)
                    }
                },
                onDismiss = { feature.dismissProblem(bookId) },
            )
        }
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
        // With the controls hidden, the wait for the voice still shows, and
        // from the tap: the session it ends up in is not there yet.
        AnimatedVisibility(
            visible = waitShown && notice == null && shown == null && (starting || (here != null && !controls)),
            enter = if (eInk) EnterTransition.None else fadeIn(),
            exit = if (eInk) ExitTransition.None else fadeOut(),
        ) {
            ChromePill(theme = theme) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .padding(vertical = 8.dp)
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
                    CancelAction(feature::stop)
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
                        VoiceChip(feature, bookId, Modifier.weight(1f, fill = false)) { openVoice(false, false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpeedButton(feature)
                            SleepButton(feature)
                            if (away) {
                                ControlButton(onReturnToVoice) {
                                    Icon(Icons.Filled.MyLocation, stringResource(R.string.read_aloud_return_to_voice))
                                }
                            }
                            ControlButton(feature::stop) {
                                Icon(Icons.Filled.Close, stringResource(R.string.read_aloud_stop))
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        if (here?.chapters == true) ChapterButton(forward = false) { feature.skipChapter(false) }
                        ControlButton(feature::skipBackward) {
                            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.read_aloud_previous_sentence))
                        }
                        PlayButton(feature, here, waitShown, theme, away)
                        ControlButton(feature::skipForward) {
                            Icon(Icons.Filled.SkipNext, stringResource(R.string.read_aloud_next_sentence))
                        }
                        if (here?.chapters == true) ChapterButton(forward = true) { feature.skipChapter(true) }
                    }
                    // Away from the voice, reading on is the other way back
                    // to it: the same words, from the page in front.
                    if (away && onReadFromHere != null) {
                        Text(
                            text = stringResource(R.string.read_aloud_from_this_page),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = 48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onReadFromHere)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The chapter before or after, marked smaller than the sentence skips it
 * stands beside since it moves further.
 */
@Composable
private fun ChapterButton(forward: Boolean, onClick: () -> Unit) {
    ControlButton(onClick) {
        Icon(
            if (forward) Icons.Filled.KeyboardDoubleArrowRight else Icons.Filled.KeyboardDoubleArrowLeft,
            stringResource(if (forward) R.string.read_aloud_next_chapter else R.string.read_aloud_previous_chapter),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Play, pause, or the wait for the voice, filled in the page's ink so the
 * one control that matters most is found without looking. While audio is
 * on its way the voice mark takes the icon's place; once playing, the
 * button still pauses. Before the first sentence has landed there is
 * nothing to pause, so it gives up the wait instead. Away from the voice
 * it resumes where the voice stands rather than where the reader is, and
 * its label says so.
 */
@Composable
private fun PlayButton(feature: SpeechReadAloud, here: ReadAloudUi?, waitShown: Boolean, theme: ReaderTheme, away: Boolean) {
    val colors = IconButtonDefaults.filledIconButtonColors(
        containerColor = theme.foreground,
        contentColor = theme.background,
    )
    val eInk = LocalEInk.current
    val preparing = stringResource(R.string.read_aloud_preparing)
    when {
        // Nothing heard yet: the first sentence is still on its way.
        here == null || (here.preparing && !here.playing && here.utterance == null) -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(PLAY_SIZE)
                .clip(CircleShape)
                .background(theme.foreground)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cancel), onClick = feature::stop)
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
            val label = stringResource(
                if (away) R.string.read_aloud_resume_listening else R.string.read_aloud_resume,
            )
            Icon(Icons.Filled.PlayArrow, label, Modifier.size(32.dp))
        }
    }
}

/** Which way out of a [ReadAloudProblem]: what the listener can do about it. */
private enum class ProblemAction { Retry, Skip, ChooseVoice, Settings }

/** What a notice leaves to be done about it, in the order they are offered. */
private fun ReadAloudNotice.answers(): List<ProblemAction> = when (this) {
    ReadAloudNotice.Network, ReadAloudNotice.Service, ReadAloudNotice.Output -> listOf(ProblemAction.Retry)
    ReadAloudNotice.RateLimited -> listOf(ProblemAction.Retry, ProblemAction.ChooseVoice)
    ReadAloudNotice.TranslationFailed -> listOf(ProblemAction.Retry, ProblemAction.Skip)
    ReadAloudNotice.InvalidVoice -> listOf(ProblemAction.ChooseVoice)
    ReadAloudNotice.InvalidKey,
    ReadAloudNotice.TermsRequired,
    ReadAloudNotice.NoSpeech,
    ReadAloudNotice.NotSetUp -> listOf(ProblemAction.Settings)
    ReadAloudNotice.SelectionNotFound, ReadAloudNotice.Unavailable -> emptyList()
}

/**
 * What went wrong, said once to a screen reader, and what can be done
 * about it. It stays over the page, with the chrome up or down, until one
 * of its answers is taken.
 */
@Composable
private fun ProblemCard(
    problem: ReadAloudProblem,
    theme: ReaderTheme,
    host: String,
    canRetry: Boolean,
    onAction: (ProblemAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Nothing to retry while no session is left to resume.
    val actions = problem.notice.answers().filter { it != ProblemAction.Retry || canRetry }
    ChromeCard(theme = theme) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(problem.notice.message(), host),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                ControlButton(onDismiss) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
            }
            if (actions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    actions.forEach { action ->
                        Text(
                            text = stringResource(action.label),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onAction(action) }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

private val ProblemAction.label: Int
    get() = when (this) {
        ProblemAction.Retry -> R.string.read_aloud_settings_server_retry
        ProblemAction.Skip -> R.string.read_aloud_problem_skip
        ProblemAction.ChooseVoice -> R.string.read_aloud_change_voice
        ProblemAction.Settings -> R.string.settings
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
 * the settings now hold. Under it, the service and model it reads with;
 * a voice or model change from the settings moves the session along, and
 * a service change ends it. A tap opens the voice sheet to change either.
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
            .widthIn(min = 48.dp)
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
        val source = service
        if (name == null || reading == null || source == null) {
            Icon(Icons.Outlined.RecordVoiceOver, stringResource(R.string.read_aloud_change_voice))
            return@Row
        }
        Icon(source.icon, contentDescription = null, modifier = Modifier.size(20.dp))
        // The text, and the same without the flag for a screen reader.
        val text = "$name · ${VoiceLabel.languageLabel(reading.language, locale)}"
        val spoken = "$name · ${Locale.forLanguageTag(reading.language).getDisplayName(locale)}"
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            Text(
                text = source.sourceLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/** Gives up the wait for the voice, as a labelled action beside it. */
@Composable
private fun CancelAction(onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.cancel),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
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
    ReadAloudNotice.NoSpeech -> R.string.read_aloud_notice_no_speech
    ReadAloudNotice.NotSetUp -> R.string.read_aloud_notice_not_set_up
    ReadAloudNotice.SelectionNotFound -> R.string.read_aloud_notice_selection_not_found
    ReadAloudNotice.Unavailable -> R.string.read_aloud_notice_unavailable
    ReadAloudNotice.TranslationFailed -> R.string.read_aloud_notice_translation_failed
}

private const val NOTICE_MS = 5_000L

private const val WAIT_SHOW_MS = 400L

private const val WAIT_HIDE_MS = 150L

private val PLAY_SIZE = 56.dp

// Long enough for "12 min", short enough that a translated "end of chapter" leaves the row its other controls.
private val SLEEP_LABEL_MAX = 72.dp
