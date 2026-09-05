package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.RemoteServer
import kotlinx.coroutines.flow.Flow

enum class LiveTopic { POSITIONS, ANNOTATIONS, INSIGHTS }

/** Credentials are compared in their stored form, never logged or sent as an identity. */
data class LiveIdentity(
    val account: String,
    val endpoint: String,
    val kind: ServerKind,
    val credential: String?,
    val device: String?,
) {
    override fun toString(): String = "LiveIdentity($kind)"

    companion object {
        fun from(server: RemoteServer): LiveIdentity = LiveIdentity(
            server.accountKey, server.baseUrl, server.kind,
            server.liseurTokenCipher, server.accountId,
        )
    }
}

data class LiveRefresh(
    val completed: Set<LiveTopic> = emptySet(),
    val owedBooks: Set<String> = emptySet(),
    val failures: Map<LiveTopic, SyncFailure> = emptyMap(),
)

interface LiveChanges {
    fun events(server: RemoteServer): Flow<Set<LiveTopic>>
    suspend fun refresh(identity: LiveIdentity, topics: Set<LiveTopic>): LiveRefresh
}
