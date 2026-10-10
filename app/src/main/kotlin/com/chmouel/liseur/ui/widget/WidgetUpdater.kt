package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Asks every Liseur widget to redraw.
 *
 * Coalesced by [RefreshCoalescer]: a position is written on every page
 * turn and a session checkpoint every minute, and the homescreen needs
 * neither at that rate. `updateAll` only reaches placed widgets, so with
 * none on the homescreen a redraw costs nothing but the lookup.
 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var coalescer: RefreshCoalescer? = null

    private val refreshes = MutableStateFlow(0L)

    /**
     * Bumped before every redraw. A widget whose Glance session is still
     * alive is only recomposed by `update`, never re-provided, so it
     * watches this to know its data has to be read again.
     */
    val generation: StateFlow<Long> = refreshes.asStateFlow()

    private fun widgets(): List<GlanceAppWidget> = listOf(CoverOnlyWidget())

    private fun supportsWidgets(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)

    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!supportsWidgets(app)) return
        val current = coalescer ?: synchronized(this) {
            coalescer ?: RefreshCoalescer(
                scope = scope,
                quietMs = QUIET_MS,
                maxWaitMs = MAX_WAIT_MS,
                now = SystemClock::elapsedRealtime,
                redraw = { updateNow(app) },
            ).also { coalescer = it }
        }
        current.request()
    }

    /**
     * Hands a redraw to WorkManager, for a receiver whose process may not
     * outlive it. A request still waiting is replaced, so a burst of them
     * redraws once.
     */
    suspend fun requestRedraw(context: Context) {
        val app = context.applicationContext
        if (!supportsWidgets(app)) return
        WorkManager.getInstance(app)
            .enqueueUniqueWork(
                ONE_OFF_REDRAW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
            )
            .await()
    }

    suspend fun updateNow(context: Context) {
        val app = context.applicationContext
        refreshes.update { it + 1 }
        for (widget in widgets()) widget.updateAll(app)
    }

    /**
     * Cancels the unique work that earlier versions queued for the hourly
     * refresh and the stats widget. Nothing queues it now, but an upgrade
     * can leave it behind in WorkManager.
     *
     * Called from app start and from the receiver when the app is updated.
     */
    fun retireLegacyWork(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                retireLegacyWorkNow(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not retire legacy widget work", e)
            }
        }
    }

    suspend fun retireLegacyWorkNow(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(LEGACY_PERIODIC_REFRESH).await()
        work.cancelUniqueWork(LEGACY_STATS_REFRESH).await()
    }

    private const val TAG = "WidgetUpdater"
    private const val QUIET_MS = 3_000L
    private const val MAX_WAIT_MS = 15_000L
    private const val ONE_OFF_REDRAW = "liseur-widget-redraw"
    private const val LEGACY_PERIODIC_REFRESH = "liseur-widget-refresh"
    private const val LEGACY_STATS_REFRESH = "liseur-widget-stats"
}

/** Redraws the widgets once, for [WidgetUpdater.requestRedraw]. */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.updateNow(applicationContext)
        return Result.success()
    }
}
