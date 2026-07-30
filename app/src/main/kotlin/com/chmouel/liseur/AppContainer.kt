package com.chmouel.liseur

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.CalibreCatalogClient
import com.chmouel.liseur.data.calibre.CalibreFileSource
import com.chmouel.liseur.data.calibre.KoboSyncRepository
import com.chmouel.liseur.data.komga.KomgaCatalogClient
import com.chmouel.liseur.data.komga.KomgaFileSource
import com.chmouel.liseur.data.komga.KomgaSyncRepository
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.library.AnnotationBackupRepository
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.RoutedPositionSync
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.settings.SessionStateRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * Manual composition root: shared Readium services and app-wide
 * dependencies. Reachable from any Context via [container].
 */
class AppContainer(context: Context) {
    private val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)

    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    val database = Room.databaseBuilder(context, LiseurDatabase::class.java, "liseur.db")
        .addMigrations(*LiseurDatabase.MIGRATIONS)
        .build()

    val libraryRepository = LocalLibraryRepository(
        context = context.applicationContext,
        assetRetriever = assetRetriever,
        publicationOpener = publicationOpener,
        bookDao = database.bookDao(),
        folderDao = database.libraryFolderDao(),
        progressDao = database.readingProgressDao(),
        annotationDao = database.annotationDao(),
    )

    /** Highlights and notes written to a file, and read back on another device. */
    val annotationBackup = AnnotationBackupRepository(
        context = context.applicationContext,
        annotationDao = database.annotationDao(),
        bookDao = database.bookDao(),
    )

    /** The one answer to whether a book is read, shared by everything that asks. */
    val finishedState = FinishedState(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val readerPreferences = ReaderPreferencesRepository(context.applicationContext)

    val appSettings = AppSettingsRepository(context.applicationContext)

    val sessionState = SessionStateRepository(context.applicationContext)

    val remoteAccount = RemoteAccountRepository(
        dao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
    )

    val bookDownloads = BookDownloadRepository(
        context = context.applicationContext,
        bookDao = database.bookDao(),
    )

    /** This device, as the servers that record who saved a position see it. */
    val deviceIdentity = DeviceIdentityRepository(context.applicationContext)

    /**
     * One account is connected, so there is one answer to how the last
     * sync went. Both implementations report here rather than each
     * keeping their own, so the settings screen never has to ask which
     * kind of server it is looking at.
     */
    val syncReporting = SyncReporting()

    val koboSync = KoboSyncRepository(
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        finishedState = finishedState,
        reporting = syncReporting,
        // What the server reported and the token that stops it being
        // reported again have to land together or not at all.
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val komgaSync = KomgaSyncRepository(
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        finishedState = finishedState,
        device = deviceIdentity,
        reporting = syncReporting,
    )

    /**
     * Which implementation each request goes to, decided by the kind of
     * server that is connected. Everything above this line is written
     * once and works for both.
     */
    val remoteRouter = RemoteRouter(
        serverDao = database.remoteServerDao(),
        catalogs = mapOf(
            ServerKind.CALIBRE to CalibreCatalogClient(),
            ServerKind.KOMGA to KomgaCatalogClient(),
        ),
        files = mapOf(
            ServerKind.CALIBRE to CalibreFileSource(),
            ServerKind.KOMGA to KomgaFileSource(),
        ),
        positions = mapOf(
            ServerKind.CALIBRE to koboSync,
            ServerKind.KOMGA to komgaSync,
        ),
    )

    val positionSync = PositionSyncCoordinator(RoutedPositionSync(remoteRouter))

    val remoteCatalog = RemoteCatalogRepository(
        account = remoteAccount,
        router = remoteRouter,
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
    )
}

val Context.container: AppContainer
    get() = (applicationContext as LiseurApplication).container
