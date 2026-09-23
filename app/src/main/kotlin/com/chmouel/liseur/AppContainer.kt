package com.chmouel.liseur

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.chmouel.liseur.data.AndroidNetworkAvailability
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.CalibreCatalogClient
import com.chmouel.liseur.data.calibre.CalibreFileSource
import com.chmouel.liseur.data.calibre.KoboSyncRepository
import com.chmouel.liseur.data.calibre.BulkDownloadStore
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
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.BookUploadRepository
import com.chmouel.liseur.data.remote.UploadPrompts
import com.chmouel.liseur.data.remote.CompositePositionSync
import com.chmouel.liseur.data.remote.AndroidLocalNetworkAccess
import com.chmouel.liseur.data.remote.LocalNetworkGuardedSync
import com.chmouel.liseur.data.liseursync.LiseurSyncAnnotations
import com.chmouel.liseur.data.liseursync.LiseurSyncSettings
import com.chmouel.liseur.data.liseursync.LiseurSyncCatalogClient
import com.chmouel.liseur.data.liseursync.LiseurSyncDeleteClient
import com.chmouel.liseur.data.liseursync.LiseurSyncFileSource
import com.chmouel.liseur.data.liseursync.LiseurSyncInsights
import com.chmouel.liseur.data.liseursync.LiseurSyncSnapshots
import com.chmouel.liseur.data.liseursync.LiseurSyncLive
import com.chmouel.liseur.data.liseursync.LiseurSyncPositionSync
import com.chmouel.liseur.data.liseursync.LiseurSyncServerSetup
import com.chmouel.liseur.data.liseursync.LiseurSyncSeriesClient
import com.chmouel.liseur.data.liseursync.LiseurSyncUploadClient
import com.chmouel.liseur.data.liseursync.WorkResolver
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.kosync.KosyncPositionSync
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.LiveRefresh
import com.chmouel.liseur.data.remote.LiveTopic
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.SeriesExtrasRepository
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.RoutedPositionSync
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.reader.ReaderPresence
import com.chmouel.liseur.data.settings.SettingsChangeTracker
import com.chmouel.liseur.data.settings.SettingsSyncRepository
import com.chmouel.liseur.data.settings.syncableSettings
import com.chmouel.liseur.data.settings.SessionStateRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.LiveSyncConnector
import com.chmouel.liseur.sync.PositionSyncWorker
import com.chmouel.liseur.sync.ReadingPositionPublisher
import com.chmouel.liseur.ui.eink.EInkDisplay
import com.chmouel.liseur.ui.eink.OnyxEInkDisplay
import com.chmouel.liseur.sync.SyncScope
import android.util.Log
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual composition root: shared Readium services and app-wide
 * dependencies. Reachable from any Context via [container].
 */
class AppContainer(context: Context) {
    val networkAvailability = AndroidNetworkAvailability(context.applicationContext)

    /**
     * The maker's screen controller, if this device turns out to have
     * one. Bound once, lazily, because the answer cannot change while
     * the process lives and looking is a handful of failed class loads
     * on every device that is not an e-reader.
     */
    val eInkDisplay: EInkDisplay by lazy { OnyxEInkDisplay.bind() }

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
        progressDao = database.readingProgressDao(),
        annotationDao = database.annotationDao(),
        annotationSyncDao = database.annotationSyncDao(),
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

    val libraryRepository = LocalLibraryRepository(
        context = context.applicationContext,
        assetRetriever = assetRetriever,
        publicationOpener = publicationOpener,
        bookDao = database.bookDao(),
        folderDao = database.libraryFolderDao(),
        bookRemoval = bookRemoval,
        fingerprints = bookFingerprints,
    )

    /** Highlights and notes written to a file, and read back on another device. */
    val annotationBackup = AnnotationBackupRepository(
        context = context.applicationContext,
        annotationDao = database.annotationDao(),
        bookDao = database.bookDao(),
        requestBookSync = ::requestBookSync,
    )

    /** The one answer to whether a book is read, shared by everything that asks. */
    val finishedState = FinishedState(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val readerPreferences = ReaderPreferencesRepository(context.applicationContext)

    val userFonts = UserFontRepository(context.applicationContext, applicationScope)

    /** What the app has learned about how fast this reader reads. */
    val readingPace = ReadingPaceRepository(context.applicationContext)

    val appSettings = AppSettingsRepository(context.applicationContext)

    val sessionState = SessionStateRepository(context.applicationContext)

    /** The one bulk download that is running, or was last. */
    val bulkDownloads = BulkDownloadStore(context.applicationContext)

    val settingsSyncState = SettingsSyncRepository(context.applicationContext)

    /**
     * BookOrbit's session: the one place its access token is minted and
     * renewed. Built here rather than inside a client so that the
     * catalog, the download worker and the cover loader all reach the
     * same one, and a renewal done for any of them is seen by the rest.
     */
    val bookOrbitSession = com.chmouel.liseur.data.bookorbit.BookOrbitSession(
        serverDao = database.remoteServerDao(),
    )

    private val bookOrbitHttp = com.chmouel.liseur.data.bookorbit.BookOrbitHttp(bookOrbitSession)
    val bookOrbitCfis = com.chmouel.liseur.data.bookorbit.BookOrbitCfiRepository(database)
    val bookOrbitProgress = com.chmouel.liseur.data.bookorbit.BookOrbitProgressClient(
        bookOrbitHttp, bookOrbitCfis,
    )
    val bookOrbitAgreement = com.chmouel.liseur.data.bookorbit.BookOrbitPositionAgreementRepository(
        database, bookOrbitProgress,
        com.chmouel.liseur.data.bookorbit.BookOrbitProgressMutationTransport(bookOrbitHttp, bookOrbitCfis),
    )
    val bookOrbitExchange = com.chmouel.liseur.data.bookorbit.BookOrbitPositionExchange(
        bookOrbitCfis, bookOrbitProgress, bookOrbitAgreement,
    )
    val bookOrbitEpubInfo = com.chmouel.liseur.data.bookorbit.BookOrbitEpubInfoClient(
        bookOrbitHttp, bookOrbitCfis,
    )

    val remoteAccount = RemoteAccountRepository(
        dao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        bookRemoval = bookRemoval,
        seriesExtraDao = database.seriesExtraDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        sessionDao = database.readingSessionDao(),
        annotationSyncDao = database.annotationSyncDao(),
        uploadRefusalDao = database.uploadRefusalDao(),
        sessionRefusalDao = database.sessionRefusalDao(),
        sessionTransmissionDao = database.sessionTransmissionDao(),
        starterProgressDao = database.starterCatalogProgressDao(),
        settingsSyncState = settingsSyncState,
        // Declared later in this file, so it is reached through the
        // lambda rather than held: the pairing is only ever touched
        // after a connection has landed, never while one is being built.
        kosync = { kosyncAccount },
        bookOrbit = bookOrbitSession,
        bookOrbitBindingDao = database.bookOrbitBindingDao(),
        bookOrbitCfiDao = database.bookOrbitCfiDao(),
        bookOrbitLocalCfiDao = database.bookOrbitLocalCfiDao(),
        bookOrbitPositionAgreementDao = database.bookOrbitPositionAgreementDao(),
        setups = mapOf(
            ServerKind.CALIBRE to com.chmouel.liseur.data.calibre.CalibreSetupClient(),
            ServerKind.KOMGA to com.chmouel.liseur.data.komga.KomgaSetupClient(),
            // The device token is minted in the device's own name, since
            // the server shows it in its device list.
            ServerKind.LISEUR_SYNC to LiseurSyncServerSetup(
                deviceName = { deviceIdentity.current().name },
            ),
            // BookOrbit records a device label on the session it issues,
            // and shows it in its own session list, so it is the same
            // name the phone goes by elsewhere.
            ServerKind.BOOKORBIT to com.chmouel.liseur.data.bookorbit.BookOrbitSetupClient(
                deviceLabel = { deviceIdentity.current().name },
                session = bookOrbitSession,
            ),
            ServerKind.CUSTOM to com.chmouel.liseur.data.opds.OpdsSetupClient(),
        ),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val bookDownloads = BookDownloadRepository(
        context = context.applicationContext,
        bookDao = database.bookDao(),
        bookRemoval = bookRemoval,
        scope = applicationScope,
        bulkStore = bulkDownloads,
        accountKey = { database.remoteServerDao().get()?.accountKey },
    )

    val bookUploads = BookUploadRepository(context.applicationContext)

    /** Which books have already been asked about this run. */
    val uploadPrompts = UploadPrompts()

    /** This device, as the servers that record who saved a position see it. */
    val deviceIdentity = DeviceIdentityRepository(context.applicationContext)

    /**
     * One account is connected, so there is one answer to how the last
     * sync went. Both implementations report here rather than each
     * keeping their own, so the settings screen never has to ask which
     * kind of server it is looking at.
     */
    val syncReporting = SyncReporting()

    /**
     * What the phone will and will not let the app reach, and the way
     * to ask it for more.
     */
    val localNetwork = AndroidLocalNetworkAccess(context)

    val koboSync = KoboSyncRepository(
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        finishedState = finishedState,
        reporting = syncReporting,
        networkAvailability = networkAvailability,
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
        networkAvailability = networkAvailability,
        inTransaction = { work -> database.withTransaction { work() } },
    )

    /** What a liseur-sync server calls each book, cached per account. */
    val workResolver = WorkResolver(
        dao = database.workIdentityDao(),
        fingerprints = bookFingerprints,
    )

    /** The KOReader kosync partner paired alongside a Custom catalog. */
    val kosyncAccount = KosyncAccountRepository(
        dao = database.kosyncPeerDao(),
        peerStateDao = database.syncPeerStateDao(),
        reporting = syncReporting,
    )

    val kosyncSync = KosyncPositionSync(
        kosyncDao = database.kosyncPeerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        peerStateDao = database.syncPeerStateDao(),
        fingerprints = bookFingerprints,
        device = { deviceIdentity.current() },
        finishedState = finishedState,
        connectedServer = { database.remoteServerDao().get() },
        reporting = syncReporting,
        networkAvailability = networkAvailability,
        inTransaction = { work -> database.withTransaction { work() } },
    )

    /**
     * liseur-sync's position sync: the append-only op log, bound to the
     * catalog account like the other kinds' syncs are.
     */
    /** Highlights, notes and bookmarks across devices (ADR-0028). */
    private val liseurSyncAnnotations = LiseurSyncAnnotations(
        serverDao = database.remoteServerDao(),
        annotationDao = database.annotationDao(),
        syncDao = database.annotationSyncDao(),
        identityDao = database.workIdentityDao(),
        inTransaction = { work -> database.withTransaction { work() } },
    )

    private val syncableSettings = syncableSettings(appSettings, readerPreferences)

    private val liseurSyncSettings = LiseurSyncSettings(
        syncState = settingsSyncState,
        settings = syncableSettings,
    )

    /**
     * Notes when the reader changes a setting, so a conflict is settled
     * on when the edit was made rather than on which device reached the
     * network first. Started from the container because it has to be
     * watching whether or not anything is connected: a change made
     * offline is exactly the one whose time cannot be recovered later.
     */
    private val settingsChangeTracker = SettingsChangeTracker(
        syncState = settingsSyncState,
        settings = syncableSettings,
        sources = listOf(readerPreferences.prefs, appSettings.settings),
    ).also { it.start(applicationScope) }

    val liseurSync = LiseurSyncPositionSync(
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        sessionDao = database.readingSessionDao(),
        sessionRefusalDao = database.sessionRefusalDao(),
        sessionTransmissionDao = database.sessionTransmissionDao(),
        supportsMeasuredSessions = { syncSnapshots.supportsMeasuredSessions() },
        works = workResolver,
        deviceKey = { deviceIdentity.current().id },
        finishedState = finishedState,
        reporting = syncReporting,
        networkAvailability = networkAvailability,
        annotations = liseurSyncAnnotations,
        settingsSync = liseurSyncSettings,
        readerIsOpen = { ReaderPresence.isOpen },
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
            ServerKind.CUSTOM to com.chmouel.liseur.data.opds.OpdsCatalogClient(
                // The size a starter shelf was asked for is the
                // connection's own, read from the row that stored it. A
                // background refresh has no idea what the reader
                // picked, and a shelf that came back a different size
                // every run would grow or shrink under them.
                //
                // Never worked out from the address: a reader may have
                // typed the same Gutenberg feed into the Book server
                // form themselves, and capping *their* catalog would
                // reconcile everything past the cap as gone.
                shelfLimit = { url ->
                    database.remoteServerDao().get()
                        ?.takeIf {
                            com.chmouel.liseur.data.remote.RemoteUrl.sameAddress(
                                it.catalogUrl ?: it.baseUrl,
                                url,
                            )
                        }
                        ?.shelfLimit
                },
            ),
            ServerKind.BOOKORBIT to com.chmouel.liseur.data.bookorbit.BookOrbitCatalogClient(
                bindings = database.bookOrbitBindingDao(),
                serverDao = database.remoteServerDao(),
                http = bookOrbitHttp,
                inTransaction = { work -> database.withTransaction { work() } },
            ),
        ),
        files = mapOf(
            ServerKind.CALIBRE to CalibreFileSource(),
            ServerKind.KOMGA to KomgaFileSource(),
            ServerKind.LISEUR_SYNC to LiseurSyncFileSource(),
            ServerKind.CUSTOM to com.chmouel.liseur.data.opds.OpdsFileSource(),
            ServerKind.BOOKORBIT to com.chmouel.liseur.data.bookorbit.BookOrbitFileSource(
                session = bookOrbitSession,
            ),
        ),
        // Custom has no entry because OPDS has no notion of a reading
        // position. It keeps a place through the KOReader pairing
        // instead, which is a peer of its own alongside the catalog.
        positions = mapOf(
            ServerKind.CALIBRE to koboSync,
            ServerKind.KOMGA to komgaSync,
            ServerKind.LISEUR_SYNC to liseurSync,
        ),
        seriesClaims = mapOf(
            ServerKind.LISEUR_SYNC to LiseurSyncSeriesClient(),
        ),
        // Komga has no entry: deleting a file there is an
        // administrator's job, and the action stays hidden rather than
        // offered and failed. liseur-sync deletes only what it could
        // have written — a book in a folder marked as accepting uploads
        // (ADR-0025) — and says so per book, so the entry here is a
        // promise the route exists, not that any given book will go.
        deleters = mapOf(
            ServerKind.CALIBRE to com.chmouel.liseur.data.calibre.CalibreBookDeleter(),
            ServerKind.LISEUR_SYNC to LiseurSyncDeleteClient(),
        ),
        // liseur-sync is the only one that takes a book, and only into a
        // folder an administrator marked (ADR-0023). Komga and
        // calibre-web have upload routes of their own; they are not
        // wired here because the app has nothing to say to them yet, and
        // an entry in this map is a promise that the action works.
        uploaders = mapOf(
            ServerKind.LISEUR_SYNC to LiseurSyncUploadClient(),
        ),
        live = mapOf(
            ServerKind.LISEUR_SYNC to LiseurSyncLive(
                refreshTopics = { identity, topics ->
                    val readingTopics = topics - LiveTopic.INSIGHTS
                    val result = if (readingTopics.isEmpty()) {
                        LiveRefresh()
                    } else {
                        liseurSync.refresh(identity, readingTopics)
                    }
                    if (LiveTopic.INSIGHTS in topics &&
                        database.remoteServerDao().get()?.let(LiveIdentity::from) == identity
                    ) {
                        _insightInvalidations.value += 1
                        result.copy(completed = result.completed + LiveTopic.INSIGHTS)
                    } else result
                },
            ),
        ),
    )

    /**
     * Every partner reading positions are kept in step with.
     *
     * The catalog server first — it is the one whose own interface shows
     * the position too — and the kosync partner after it. The composite
     * runs them in turn, so the coordinator's ordering rules hold across
     * both without being written twice.
     *
     * Each is wrapped against a phone that is blocking the network its
     * server lives on, separately rather than together: a public
     * catalog and a kosync server on the LAN is an ordinary pairing,
     * and one of them being out of reach is no reason to stop syncing
     * with the other.
     */
    val positionSync = PositionSyncCoordinator(
        CompositePositionSync(
            listOf(
                LocalNetworkGuardedSync(
                    delegate = RoutedPositionSync(remoteRouter),
                    access = localNetwork,
                    reporting = syncReporting,
                ),
                LocalNetworkGuardedSync(
                    delegate = kosyncSync,
                    access = localNetwork,
                    reporting = syncReporting,
                ),
            ),
        ),
        carryOn = { PositionSyncWorker.continueBootstrap(context.applicationContext) },
    )

    private val latestPositionSync = LatestPositionSync(
        scope = applicationScope,
        request = { positionSync.request(SyncScope.Book(it)) },
        scheduleRetry = { PositionSyncWorker.retryBook(context.applicationContext, it) },
        onError = { message, error -> Log.e("position-sync", message, error) },
    )

    fun requestBookSync(bookId: String) = latestPositionSync.signal(bookId)

    private val _insightInvalidations = MutableStateFlow(0L)
    val insightInvalidations = _insightInvalidations.asStateFlow()

    val liveSync = LiveSyncConnector(
        scope = applicationScope,
        accounts = database.remoteServerDao().observe(),
        sourceFor = { server ->
            // A live connection is one more socket to the same machine
            // the position sync dials, so it answers to the same block:
            // opening it while the permission is refused buys a retry
            // loop of timeouts and no events. It reports nothing —
            // the guarded sync already says the account is blocked —
            // and the next connection picks up a grant.
            remoteRouter.liveFor(server.kind)
                .takeIf { server.credentials != null }
                ?.takeIf { !localNetwork.blocks(server.baseUrl) }
        },
        coordinator = positionSync,
        reconnectOn = localNetwork.grants,
        requestBook = ::requestBookSync,
        reportFailure = { identity, reason ->
            if (database.remoteServerDao().get()?.let(LiveIdentity::from) == identity) {
                syncReporting.report(com.chmouel.liseur.data.remote.PositionSyncStatus.Failed(reason))
            }
        },
    )

    val readingPositions = ReadingPositionPublisher(
        scope = applicationScope,
        overrideFor = { database.readingProgressDao().get(it)?.override ?: com.chmouel.liseur.domain.FinishedOverride.NONE },
        persist = com.chmouel.liseur.data.bookorbit.BookOrbitLocalPositionWriter(database)::save,
        refreshFinished = finishedState::refreshFromProgress,
        markFinished = { finishedState.setFinished(it, true) },
        latestSync = latestPositionSync,
        scheduleClose = { PositionSyncWorker.pushBook(context.applicationContext, it) },
        onError = { message, error -> Log.e("reading-position", message, error) },
    )

    /** Reading added up across every device, when a server keeps it. */
    val syncInsights = LiseurSyncInsights(
        serverDao = database.remoteServerDao(),
        identityDao = database.workIdentityDao(),
    )

    val syncSnapshots = LiseurSyncSnapshots(
        database.remoteServerDao(), database.readingSessionDao(),
        database.sessionTransmissionDao(), database.workIdentityDao(),
        deviceKey = { deviceIdentity.current().id },
    )

    val remoteCatalog = RemoteCatalogRepository(
        router = remoteRouter,
        serverDao = database.remoteServerDao(),
        bookDao = database.bookDao(),
        starterProgressDao = database.starterCatalogProgressDao(),
        bookRemoval = bookRemoval,
        inTransaction = { work -> database.withTransaction { work() } },
        networkAvailability = networkAvailability,
        localNetwork = localNetwork,
    )

    val seriesExtras = SeriesExtrasRepository(
        account = remoteAccount,
        dao = database.seriesExtraDao(),
        networkAvailability = networkAvailability,
    )
}

val Context.container: AppContainer
    get() = (applicationContext as LiseurApplication).container
