package com.chmouel.liseur.tts

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Keeps read-aloud playing with the reader gone: the media notification,
 * the lock screen and headset controls, and the foreground state that
 * keeps the process and its network alive with the screen off.
 *
 * It only shows the session [GeminiReadAloud] holds, and stops itself
 * once there is none.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)
class ReadAloudService : MediaSessionService() {

    private val scope = MainScope()
    private var mediaSession: MediaSession? = null
    private var feature: GeminiReadAloud? = null

    /** The session this service shows; a replacement starting meanwhile is not this one's to pause. */
    private var shown: ReadAloudSession? = null

    override fun onCreate() {
        super.onCreate()
        val feature = application.container.readAloud as? GeminiReadAloud
        this.feature = feature
        if (feature == null) {
            stopSelf()
            return
        }
        scope.launch {
            feature.current
                .flatMapLatest { session ->
                    session?.playback?.navigator?.map { navigator -> session to navigator } ?: flowOf(null)
                }
                .collect { current ->
                    if (current == null) {
                        shown = null
                        release()
                        stopSelf()
                        return@collect
                    }
                    val (session, navigator) = current
                    navigator ?: return@collect
                    show(session, SessionPlayer(navigator.asMedia3Player(), session))
                }
        }
    }

    private fun show(session: ReadAloudSession, player: Player) {
        shown = session
        val existing = mediaSession
        if (existing != null) {
            existing.player = player
            existing.setSessionActivity(activityFor(session))
            return
        }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(activityFor(session))
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setDisplayName(getString(R.string.read_aloud_previous_sentence))
                        .setSessionCommand(PREVIOUS)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setDisplayName(getString(R.string.read_aloud_next_sentence))
                        .setSessionCommand(NEXT)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
            .also(::addSession)
    }

    private fun activityFor(session: ReadAloudSession): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // A reader already in front is brought back rather than stacked again.
        Intent(session.reader).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession?.takeIf { accepts(it, controllerInfo) }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away: playing carries on, a paused book is put away.
        val session = feature?.current?.value
        if (session?.ui?.value?.playing != true) {
            session?.stop()
            release()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Destroyed under a playing voice, nothing would keep it alive: pause it.
        shown?.pause()
        release()
        super.onDestroy()
    }

    private fun release() {
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!accepts(session, controller)) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(PREVIOUS)
                        .add(NEXT)
                        .build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val current = feature?.current?.value
            when (customCommand.customAction) {
                PREVIOUS.customAction -> current?.skipBackward()
                NEXT.customAction -> current?.skipForward()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun accepts(session: MediaSession, controller: MediaSession.ControllerInfo) =
        ReadAloudControllerPolicy.accepts(
            controllerUid = controller.uid,
            ownUid = Process.myUid(),
            trusted = controller.isTrusted,
            notificationController = session.isMediaNotificationController(controller),
        )

    /**
     * Routes play, pause and stop through the session, so playing after a
     * failure starts again at the failed sentence and the place heard is
     * saved, whichever controller asked.
     */
    private class SessionPlayer(player: Player, private val session: ReadAloudSession) : ForwardingPlayer(player) {
        override fun play() = session.resume()

        override fun pause() = session.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) session.resume() else session.pause()
        }

        override fun stop() = session.stop()
    }

    private companion object {
        val PREVIOUS = SessionCommand("com.chmouel.liseur.tts.PREVIOUS_SENTENCE", Bundle.EMPTY)
        val NEXT = SessionCommand("com.chmouel.liseur.tts.NEXT_SENTENCE", Bundle.EMPTY)
    }
}
