package com.chmouel.liseur

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.chmouel.liseur.data.calibre.CalibreAccountRepository
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.CalibreCatalogRepository
import com.chmouel.liseur.data.calibre.KoboSyncRepository
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
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

    val calibreAccount = CalibreAccountRepository(
        dao = database.calibreServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
    )

    val bookDownloads = BookDownloadRepository(
        context = context.applicationContext,
        bookDao = database.bookDao(),
    )

    val koboSync = KoboSyncRepository(
        serverDao = database.calibreServerDao(),
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        finishedState = finishedState,
        // What the server reported and the token that stops it being
        // reported again have to land together or not at all.
        inTransaction = { work -> database.withTransaction { work() } },
    )

    val positionSync = PositionSyncCoordinator(koboSync)

    val calibreCatalog = CalibreCatalogRepository(
        account = calibreAccount,
        serverDao = database.calibreServerDao(),
        bookDao = database.bookDao(),
    )
}

val Context.container: AppContainer
    get() = (applicationContext as LiseurApplication).container
