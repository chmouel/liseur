package com.chmouel.liseur.providers

import android.content.Context
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.drive.DriveLibrarySync
import com.chmouel.liseur.drive.DriveSyncWorkers
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator

/** The Play build registers Google Drive as its flavor-specific library-sync backend. */
object LibrarySyncBackendFactory {
    fun create(
        context: Context,
        database: LiseurDatabase,
        remoteAccount: RemoteAccountRepository,
        bookRemoval: BookRemoval? = null,
        switchCoordinator: LibrarySyncSwitchCoordinator = LibrarySyncSwitchCoordinator(),
        beforeServerDisconnect: suspend () -> Unit = {},
    ): LibrarySyncBackend {
        val appContext = context.applicationContext
        return DriveLibrarySync(
            context = appContext,
            database = database,
            remoteAccount = remoteAccount,
            bookRemoval = bookRemoval,
            switchCoordinator = switchCoordinator,
            beforeServerDisconnect = beforeServerDisconnect,
            stopWorkers = { DriveSyncWorkers.cancelAll(appContext) },
            onResumeActive = {
                DriveSyncWorkers.schedulePeriodicStateSync(appContext)
                DriveSyncWorkers.enqueueStateSync(appContext)
            },
            scheduleEpubUploadsWork = { allowMetered ->
                DriveSyncWorkers.enqueueEpubUploads(appContext, allowMetered = allowMetered)
            },
            appendEpubUploadsWork = { allowMetered ->
                DriveSyncWorkers.enqueueEpubUploads(
                    appContext,
                    allowMetered = allowMetered,
                    policy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                )
            },
            cancelEpubUploadsWork = {
                DriveSyncWorkers.cancelEpubUploads(appContext)
            },
            enqueueEpubDownloadWork = { bookUrl, allowMetered ->
                DriveSyncWorkers.enqueueEpubDownload(appContext, bookUrl = bookUrl, allowMetered = allowMetered)
            },
            cancelEpubDownloadWork = { bookUrl ->
                DriveSyncWorkers.cancelEpubDownload(appContext, bookUrl = bookUrl)
            },
        )
    }
}
