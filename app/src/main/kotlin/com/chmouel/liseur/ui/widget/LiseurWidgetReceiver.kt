package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the three Liseur receivers share: the hourly refresh follows
 * whether any widget is placed, and a clock or time zone change redraws,
 * since "today" and "this week" may now be different days. A language
 * change redraws too: the labels and the week start were baked in when
 * the widget was last drawn.
 *
 * Any of these broadcasts may have started the process on its own, and
 * Android can kill it as soon as the receiver is done, so each one holds
 * the broadcast open until its work is handed to WorkManager.
 *
 * The receivers are exported for the launcher, so any app can send them
 * these actions. A redraw request replaces the one still waiting, so a
 * burst of them costs one redraw, and nothing when no widget is placed.
 */
abstract class LiseurWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> {
                holdingBroadcast { WidgetUpdater.requestRedraw(context) }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        holdingBroadcast { WidgetUpdater.reconcilePeriodicNow(context) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        holdingBroadcast { WidgetUpdater.reconcilePeriodicNow(context) }
    }

    private fun holdingBroadcast(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Widget broadcast work failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LiseurWidgetReceiver"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
