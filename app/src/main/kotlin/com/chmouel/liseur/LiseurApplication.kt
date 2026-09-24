package com.chmouel.liseur

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.chmouel.liseur.data.remote.RemoteAuthInterceptor
import com.chmouel.liseur.domain.shouldSyncOnForeground
import com.chmouel.liseur.sync.PositionSyncWorker
import com.chmouel.liseur.sync.SyncScope
import com.chmouel.liseur.ui.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LiseurApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)
    private var lastForegroundSyncAt = 0L

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A restored backup brings the account with it but not the key
        // that unlocks it, so check before anything tries to use it.
        appScope.launch {
            // Dropping it silently looks exactly like the account never
            // existing, so the calibre screen is left something to say.
            if (container.remoteAccount.forgetUnreadableAccount()) {
                container.appSettings.setAccountLostToRestore(true)
            }
            // Covers are fetched from the server and need signing, from
            // an image loader that cannot wait on the database. Read the
            // account now so the first screenful does not have to.
            container.remoteAccount.prime()
            container.bookOrbitSession.prime()
        }
        PositionSyncWorker.schedulePeriodic(this)
        WidgetUpdater.reconcilePeriodic(this)
        syncWhenBroughtToTheFore()
    }

    /**
     * Syncs when the app is opened, rather than when its process happens
     * to start.
     *
     * Those are not the same thing, and treating them as if they were is
     * why positions appeared not to sync at all: Android keeps a process
     * alive for days, so coming back from the launcher ran nothing. This
     * fires on the way in instead, debounced so that flicking to another
     * app and back does not mean a round trip every time.
     */
    private fun syncWhenBroughtToTheFore() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.liveSync.foreground()
                    val now = System.currentTimeMillis()
                    if (now - lastForegroundSyncAt < FOREGROUND_SYNC_DEBOUNCE_MS) return
                    lastForegroundSyncAt = now
                    appScope.launch {
                        // A process is not a session. Whether a full sync
                        // is worth its round trips is answered from what
                        // the last one wrote down, which outlives being
                        // killed in a way a field in here does not.
                        val due = shouldSyncOnForeground(
                            container.remoteAccount.current(),
                            now,
                            kosync = container.kosyncAccount.current(),
                        )
                        if (!due) return@launch
                        runCatching { container.positionSync.request(SyncScope.Full, now) }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.liveSync.background()
                }
            },
        )
    }

    /**
     * Covers come straight from calibre-web, which wants the login on
     * every request, and are kept on disk so the library scrolls without
     * going back to the server each time.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            RemoteAuthInterceptor.imageLoaderClient(
                                bookOrbitAuth = com.chmouel.liseur.data.bookorbit.BookOrbitNetworkAuth(
                                    container.bookOrbitSession,
                                    inferCurrentContext = true,
                                ),
                                credentialsFor = { url ->
                                    if (com.chmouel.liseur.data.bookorbit.BookOrbitUrl.isScopedCover(url)) {
                                        // Do not fall back to the generic
                                        // current-account signer: a scoped
                                        // cover that no longer belongs to
                                        // the current BookOrbit account is
                                        // an old queued request and must go
                                        // out unsigned rather than with the
                                        // new account's token.
                                        container.bookOrbitSession.cachedCredentialsForUrl(url)
                                    } else {
                                        container.remoteAccount.credentialsForUrl(url)
                                    }
                                },
                                requestPolicy = { request ->
                                    val context = request.tag(
                                        com.chmouel.liseur.data.bookorbit.BookOrbitRequestContext::class.java,
                                    )
                                    when {
                                        context == null -> com.chmouel.liseur.data.remote.RequestCredentialPolicy.DEFAULT
                                        context.covers(request.url.toString()) ->
                                            com.chmouel.liseur.data.remote.RequestCredentialPolicy.PRESERVE
                                        else -> com.chmouel.liseur.data.remote.RequestCredentialPolicy.STRIP
                                    }
                                },
                            )
                        },
                    ),
                )
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        /** Long enough to ignore app switching, short enough to feel live. */
        const val FOREGROUND_SYNC_DEBOUNCE_MS = 60_000L
    }
}
