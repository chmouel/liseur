package com.chmouel.liseur.ui.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.chmouel.liseur.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a widget tap asked the app to go: the dashboard, the library, or one book in it. */
data class WidgetRequest(val stats: Boolean, val bookUrl: String?)

/**
 * Widget requests handed to [MainActivity] inside the process.
 * [MainActivity] is exported, so extras on its intent could come from any app; only
 * [WidgetLaunchActivity], which is not exported, may post here. A request lives only in
 * memory: the post and the start of [MainActivity] happen back to back in one process,
 * and a process killed in between loses nothing worse than one tap.
 */
object WidgetRequests {
    private val state = MutableStateFlow<WidgetRequest?>(null)
    val pending: StateFlow<WidgetRequest?> = state.asStateFlow()

    internal fun post(request: WidgetRequest) {
        state.value = request
    }

    /** Clears [request] only, so a newer tap that arrived meanwhile survives. */
    fun consume(request: WidgetRequest) {
        state.compareAndSet(request, null)
    }
}

/** Unexported entry point for widget taps; the launcher holds its PendingIntent. */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            WidgetRequests.post(
                WidgetRequest(
                    stats = intent.getBooleanExtra(EXTRA_STATS, false),
                    bookUrl = intent.getStringExtra(EXTRA_BOOK),
                ),
            )
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_STATS = "stats"
        private const val EXTRA_BOOK = "book"

        fun intent(context: Context, stats: Boolean = false, bookUrl: String? = null): Intent =
            Intent(context, WidgetLaunchActivity::class.java)
                .putExtra(EXTRA_STATS, stats)
                .putExtra(EXTRA_BOOK, bookUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
