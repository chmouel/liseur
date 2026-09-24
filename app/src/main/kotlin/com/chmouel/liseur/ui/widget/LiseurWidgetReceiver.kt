package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * What the three Liseur receivers share: the hourly refresh follows
 * whether any widget is placed, and a clock or time zone change redraws,
 * since "today" and "this week" may now be different days. A language
 * change redraws too: the labels and the week start were baked in when
 * the widget was last drawn.
 *
 * The receivers are exported for the launcher, so any app can send them
 * these actions. They only ask for a coalesced redraw, which is cheap and
 * does nothing when no widget is placed.
 */
abstract class LiseurWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> {
                WidgetUpdater.schedule(context)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.reconcilePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.reconcilePeriodic(context)
    }
}
