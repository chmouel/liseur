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
    SyncFailure.Offline -> R.string.calibre_sync_offline
    SyncFailure.Timeout -> R.string.calibre_sync_timeout
    SyncFailure.Unauthorised -> R.string.calibre_sync_unauthorised
    SyncFailure.Forbidden -> R.string.calibre_sync_forbidden
    SyncFailure.NotFound -> R.string.calibre_sync_missing
    SyncFailure.Malformed -> R.string.calibre_sync_malformed
    is SyncFailure.ServerError -> R.string.calibre_sync_server_error
}
