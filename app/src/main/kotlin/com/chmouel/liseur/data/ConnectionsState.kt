package com.chmouel.liseur.data

import com.chmouel.liseur.data.db.RemoteServer
import kotlinx.coroutines.flow.Flow

/**
 * Which server is connected, for settings to show at a glance.
 *
 * The flow is already observed for the account screen; this only names
 * it so the settings row can say what it is connected to rather than
 * repeat the invitation to connect.
 */
class ConnectionsState(
    val catalog: Flow<RemoteServer?>,
)
