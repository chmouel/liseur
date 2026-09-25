package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.LiseurDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The tables every widget reads. A new table that changes what a widget
 * shows belongs here, or the widget will not redraw when it changes.
 */
internal val WIDGET_TABLES = arrayOf(
    "books",
    "reading_progress",
    "reading_sessions",
    "remote_stats_day",
    "remote_stats_window",
)

/**
 * One emission per change to what the widgets read, plus one straight
 * away for the state they may have missed while the process was dead.
 *
 * Watching the tables instead of hooking each writer means a new write
 * path cannot forget to refresh the homescreen.
 */
fun LiseurDatabase.widgetInputs(): Flow<Set<String>> =
    invalidationTracker.createFlow(*WIDGET_TABLES)
