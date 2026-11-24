package com.chmouel.liseur.providers

import android.content.Context
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator

/** The F-Droid build has no external library-sync backend outside [RemoteAccountRepository]. */
@Suppress("UNUSED_PARAMETER")
object LibrarySyncBackendFactory {
    fun create(
        context: Context,
        database: LiseurDatabase,
        remoteAccount: RemoteAccountRepository,
        bookRemoval: BookRemoval? = null,
        switchCoordinator: LibrarySyncSwitchCoordinator = LibrarySyncSwitchCoordinator(),
        beforeServerDisconnect: suspend () -> Unit = {},
    ): LibrarySyncBackend = LibrarySyncBackend.None
}
