package com.chmouel.liseur.providers

import android.content.Context
import androidx.compose.runtime.Composable

/** The F-Droid build has no accounts of its own: only servers are set up on the Services page. */
@Suppress("UNUSED_PARAMETER")
internal class ServiceAccounts(context: Context, connections: ServerConnections) {
    /** The accounts' rows at the top of the Services page. */
    @Composable
    fun Rows() = Unit
}
