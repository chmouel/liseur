package com.chmouel.liseur

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.ConnectionsState
import com.chmouel.liseur.data.calibre.CalibreCatalogClient
import com.chmouel.liseur.data.calibre.CalibreFileSource
import com.chmouel.liseur.data.calibre.KoboSyncRepository
import com.chmouel.liseur.data.komga.KomgaCatalogClient
import com.chmouel.liseur.data.komga.KomgaFileSource
import com.chmouel.liseur.data.komga.KomgaSyncRepository
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.library.AnnotationBackupRepository
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.library.ReadingSessionManager
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReadingPaceRepository
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.CompositePositionSync
import com.chmouel.liseur.data.liseursync.BookUploadRepository
import com.chmouel.liseur.data.liseursync.LiseurSyncCatalogClient
import com.chmouel.liseur.data.liseursync.LiseurSyncFileSource
import com.chmouel.liseur.data.liseursync.LiseurSyncInsights
import com.chmouel.liseur.data.liseursync.LiseurSyncPositionSync
import com.chmouel.liseur.data.liseursync.LiseurSyncServerSetup
import com.chmouel.liseur.data.liseursync.WorkResolver
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.SeriesExtrasRepository
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.RoutedPositionSync
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.settings.SessionStateRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionSyncWorker
import com.chmouel.liseur.sync.ReadingPositionPublisher
import com.chmouel.liseur.sync.SyncScope
import android.util.Log
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual composition root: shared Readium services and app-wide
 * dependencies. Reachable from any Context via [container].
 */
class AppContainer(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    val readingSessions = ReadingSessionManager(
        dao = database.readingSessionDao(),
        scope = applicationScope,
    )

    val bookRemoval = BookRemoval(
        bookDao = database.bookDao(),
        sessionDao = database.readingSessionDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val libraryRepository = LocalLibraryRepository(
        context = context.applicationContext,
        assetRetriever = assetRetriever,
        publicationOpener = publicationOpener,
        bookDao = database.bookDao(),
        folderDao = database.libraryFolderDao(),
        progressDao = database.readingProgressDao(),
        annotationDao = database.annotationDao(),
        sessionDao = database.readingSessionDao(),
        bookRemoval = bookRemoval,
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

    /** What the app has learned about how fast this reader reads. */
    val readingPace = ReadingPaceRepository(context.applicationContext)

    val appSettings = AppSettingsRepository(context.applicationContext)

    val sessionState = SessionStateRepository(context.applicationContext)

    val remoteAccount = RemoteAccountRepository(
        dao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        bookRemoval = bookRemoval,
        seriesExtraDao = database.seriesExtraDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        setups = mapOf(
            ServerKind.CALIBRE to com.chmouel.liseur.data.calibre.CalibreSetupClient(),
            ServerKind.KOMGA to com.chmouel.liseur.data.komga.KomgaSetupClient(),
            // The device token is minted in the device's own name, since
            // the server shows it in its device list.
            ServerKind.LISEUR_SYNC to LiseurSyncServerSetup(
                deviceName = { deviceIdentity.current().name },
            ),
        ),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val bookDownloads = BookDownloadRepository(
        context = context.applicationContext,
        bookDao = database.bookDao(),
        bookRemoval = bookRemoval,
    )

    /** Pushing local EPUBs up to a liseur-sync server, when one allows it. */
    val bookUploads = BookUploadRepository(context.applicationContext)

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
        inTransaction = { work -> database.withTransaction { work() } },
    )

    /**
     * What a book's file hashes to, worked out on demand.
     *
     * Lazily, and never during a library scan: hashing a large EPUB on a
     * memory card is slow enough to be felt, and nothing needs the
     * answer until a book is named to a server.
     */
    val bookFingerprints = BookFingerprintStore(
        context = context.applicationContext,
        dao = database.workIdentityDao(),
    )

    /** What a liseur-sync server calls each book, cached per account. */
    val workResolver = WorkResolver(
        dao = database.workIdentityDao(),
        fingerprints = bookFingerprints,
    )

    /**
     * liseur-sync's position sync: the append-only op log, bound to the
     * catalog account like the other kinds' syncs are.
     */
    val liseurSync = LiseurSyncPositionSync(
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        sessionDao = database.readingSessionDao(),
        works = workResolver,
        deviceKey = { deviceIdentity.current().id },
        finishedState = finishedState,
        reporting = syncReporting,
        inTransaction = { work -> database.withTransaction { work() } },
    )

    /**
     * Which implementation each request goes to, decided by the kind of
     * server that is connected. Everything above this line is written
     * once and works for all three.
     */
    val remoteRouter = RemoteRouter(
        serverDao = database.remoteServerDao(),
        catalogs = mapOf(
            ServerKind.CALIBRE to CalibreCatalogClient(),
            ServerKind.KOMGA to KomgaCatalogClient(),
            ServerKind.LISEUR_SYNC to LiseurSyncCatalogClient(),
        ),
        files = mapOf(
            ServerKind.CALIBRE to CalibreFileSource(),
            ServerKind.KOMGA to KomgaFileSource(),
            ServerKind.LISEUR_SYNC to LiseurSyncFileSource(),
        ),
        positions = mapOf(
            ServerKind.CALIBRE to koboSync,
            ServerKind.KOMGA to komgaSync,
            ServerKind.LISEUR_SYNC to liseurSync,
        ),
    )

    /**
     * Every partner reading positions are kept in step with.
     *
     * Today that is the one connected server, whichever kind it is; the
     * composite stays because the coordinator's ordering rules are
     * written against it, and a partner added later — a dedicated sync
     * server again, say — is one list entry away.
     */
    val positionSync = PositionSyncCoordinator(
        CompositePositionSync(
            listOf(RoutedPositionSync(remoteRouter)),
        ),
    )

    private val latestPositionSync = LatestPositionSync(
        scope = applicationScope,
        request = { positionSync.request(SyncScope.Book(it)) },
        scheduleRetry = { PositionSyncWorker.retryBook(context.applicationContext, it) },
        onError = { message, error -> Log.e("position-sync", message, error) },
    )

    val readingPositions = ReadingPositionPublisher(
        scope = applicationScope,
        overrideFor = { database.readingProgressDao().get(it)?.override ?: com.chmouel.liseur.domain.FinishedOverride.NONE },
        persist = { update, status ->
            database.readingProgressDao().recordLocal(
                bookUrl = update.bookUrl,
                locatorJson = update.locatorJson,
                progression = update.progression,
                readingSecondsPerPosition = update.readingSecondsPerPosition,
                readingPaceSamples = update.readingPaceSamples,
                readingPaceElapsedMs = update.readingPaceElapsedMs,
                readingPaceEvidence = update.readingPaceEvidence,
                status = status,
                updatedAt = update.updatedAt,
            )
        },
        refreshFinished = finishedState::refreshFromProgress,
        latestSync = latestPositionSync,
        scheduleClose = { PositionSyncWorker.pushBook(context.applicationContext, it) },
        onError = { message, error -> Log.e("reading-position", message, error) },
    )

    /** Reading added up across every device, when a server keeps it. */
    val syncInsights = LiseurSyncInsights(
        serverDao = database.remoteServerDao(),
        identityDao = database.workIdentityDao(),
    )

    /**
     * Which server is connected, for settings to show at a glance.
     */
    val connections = ConnectionsState(
        catalog = remoteAccount.server,
    )

    val remoteCatalog = RemoteCatalogRepository(
        router = remoteRouter,
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        bookRemoval = bookRemoval,
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val seriesExtras = SeriesExtrasRepository(
        account = remoteAccount,
        dao = database.seriesExtraDao(),
    )
}

val Context.container: AppContainer
    get() = (applicationContext as LiseurApplication).container
