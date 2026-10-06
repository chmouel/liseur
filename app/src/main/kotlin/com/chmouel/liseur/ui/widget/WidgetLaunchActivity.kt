package com.chmouel.liseur.ui.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.ui.launch.LaunchRequests

/** Unexported entry point for widget taps; the launcher holds its PendingIntent. */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            LaunchRequests.shared.widget(
                stats = intent.getBooleanExtra(EXTRA_STATS, false),
                bookUrl = intent.getStringExtra(EXTRA_BOOK),
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
