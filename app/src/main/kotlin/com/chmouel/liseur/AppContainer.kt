package com.chmouel.liseur

import android.content.Context
import androidx.room.Room
import com.chmouel.liseur.data.calibre.CalibreAccountRepository
import com.chmouel.liseur.data.calibre.CalibreCatalogRepository
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
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
        .addMigrations(LiseurDatabase.MIGRATION_1_2, LiseurDatabase.MIGRATION_2_3)
        .build()

    val libraryRepository = LocalLibraryRepository(
        context = context.applicationContext,
        assetRetriever = assetRetriever,
        publicationOpener = publicationOpener,
        bookDao = database.bookDao(),
        folderDao = database.libraryFolderDao(),
    )

    val readerPreferences = ReaderPreferencesRepository(context.applicationContext)

    val calibreAccount = CalibreAccountRepository(database.calibreServerDao())

    val calibreCatalog = CalibreCatalogRepository(
        account = calibreAccount,
        serverDao = database.calibreServerDao(),
        bookDao = database.bookDao(),
    )
}

val Context.container: AppContainer
    get() = (applicationContext as LiseurApplication).container
