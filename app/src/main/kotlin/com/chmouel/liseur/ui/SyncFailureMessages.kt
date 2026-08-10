package com.chmouel.liseur.ui

import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.SyncFailure

/**
 * Plain words for why syncing did not work.
 *
 * Shared by the settings screen and the reader so the same problem is
 * never described two different ways depending on where you hit it.
 */
fun SyncFailure.messageRes(): Int = when (this) {
    SyncFailure.Offline -> R.string.server_sync_offline
    SyncFailure.Timeout -> R.string.server_sync_timeout
    SyncFailure.Unauthorised -> R.string.server_sync_unauthorised
    SyncFailure.Forbidden -> R.string.server_sync_forbidden
    SyncFailure.NotFound -> R.string.server_sync_missing
    SyncFailure.Malformed -> R.string.server_sync_malformed
    SyncFailure.InsecureTransport -> R.string.server_sync_insecure
    is SyncFailure.ServerError -> R.string.server_sync_server_error
}
