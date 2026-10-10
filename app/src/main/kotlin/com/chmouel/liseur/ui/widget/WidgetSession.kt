package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import com.chmouel.liseur.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One database snapshot shared by every responsive composition in a live session. */
internal class LiveSnapshot private constructor(
    private val context: Context,
    private val repository: WidgetRepository,
    initial: WidgetSnapshot,
    private val initialGeneration: Long,
) {
    private val snapshot = MutableStateFlow(initial)

    private suspend fun follow() {
        WidgetUpdater.generation.collectLatest { generation ->
            if (generation != initialGeneration) snapshot.value = repository.load(context)
        }
    }

    @Composable
    fun observe(): WidgetSnapshot {
        val value by snapshot.collectAsState()
        return value
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        suspend fun start(
            context: Context,
            id: GlanceId,
            scope: CoroutineScope,
        ): LiveSnapshot {
            val repository = widgetRepository(context)
            val generation = WidgetUpdater.generation.value
            val initial = repository.load(context)
            return LiveSnapshot(context, repository, initial, generation).also { live ->
                scope.launch { live.follow() }
            }
        }
    }
}

internal fun widgetRepository(context: Context): WidgetRepository {
    val database = context.container.database
    return WidgetRepository(
        bookDao = database.bookDao(),
    )
}
