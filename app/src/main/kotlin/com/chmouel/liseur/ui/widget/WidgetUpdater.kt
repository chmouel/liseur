package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the hourly refresh should be doing, given how many widgets are placed. */
enum class PeriodicRefresh { Enqueue, Cancel }

fun periodicRefreshFor(placedWidgets: Int): PeriodicRefresh =
    if (placedWidgets > 0) PeriodicRefresh.Enqueue else PeriodicRefresh.Cancel

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

    private fun widgets(): List<GlanceAppWidget> =
        listOf(CoverOnlyWidget(), WeekStatsWidget(), CoverStatsWidget())

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

    suspend fun updateNow(context: Context) {
        val app = context.applicationContext
        refreshes.update { it + 1 }
        for (widget in widgets()) widget.updateAll(app)
    }

    /**
     * Keeps the hourly refresh running exactly while a widget is placed.
     *
     * Idempotent, and called from app start, from the receivers when the
     * first or last instance of a provider comes or goes, and from the job
     * itself: an upgrade, a restore or a force-stop can each leave the job
     * out of step with the homescreen, and any one of these puts it back.
     */
    fun reconcilePeriodic(context: Context) {
        val app = context.applicationContext
        if (!supportsWidgets(app)) return
        scope.launch {
            try {
                reconcilePeriodicNow(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not reconcile the widget refresh", e)
            }
        }
    }

    // Without the feature (TV, Automotive, some e-reader builds) there is no
    // AppWidgetManager, and Glance's id lookup throws on every start.
    private fun supportsWidgets(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)

    suspend fun reconcilePeriodicNow(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val placed = widgets().sumOf { manager.getGlanceIds(it.javaClass).size }
        val work = WorkManager.getInstance(context)
        when (periodicRefreshFor(placed)) {
            PeriodicRefresh.Enqueue -> work.enqueueUniquePeriodicWork(
                PERIODIC_REFRESH,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS).build(),
            )
            PeriodicRefresh.Cancel -> work.cancelUniqueWork(PERIODIC_REFRESH)
        }
    }

    private const val TAG = "WidgetUpdater"
    private const val QUIET_MS = 3_000L
    private const val MAX_WAIT_MS = 15_000L
    private const val PERIODIC_REFRESH = "liseur-widget-refresh"
}

/**
 * Hourly redraw, so the day and week roll over on the homescreen
 * without the app being opened. Manifest receivers no longer hear
 * `DATE_CHANGED`, so this is what moves the widget past midnight.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.updateNow(applicationContext)
        WidgetUpdater.reconcilePeriodicNow(applicationContext)
        return Result.success()
    }
}
