package com.chmouel.liseur.data

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SyncAccount
import kotlinx.coroutines.flow.Flow

/**
 * Which servers are connected, for settings to show at a glance.
 *
 * Both halves are observed for their own screens already; this only
 * carries the two flows together so the settings rows can say what they
 * are connected to rather than repeat the invitation to connect.
 */
class ConnectionsState(
    val catalog: Flow<RemoteServer?>,
    val sync: Flow<SyncAccount?>,
)
