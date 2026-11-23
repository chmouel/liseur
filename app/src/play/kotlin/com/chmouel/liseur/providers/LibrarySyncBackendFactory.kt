package com.chmouel.liseur.providers

import android.content.Context
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.drive.DriveLibrarySync
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator

/** The Play build registers Google Drive as its flavor-specific library-sync backend. */
object LibrarySyncBackendFactory {
    fun create(
        context: Context,
        database: LiseurDatabase,
        remoteAccount: RemoteAccountRepository,
        switchCoordinator: LibrarySyncSwitchCoordinator = LibrarySyncSwitchCoordinator(),
        beforeServerDisconnect: suspend () -> Unit = {},
    ): LibrarySyncBackend = DriveLibrarySync(
        context = context.applicationContext,
        database = database,
        remoteAccount = remoteAccount,
        switchCoordinator = switchCoordinator,
        beforeServerDisconnect = beforeServerDisconnect,
    )
}
