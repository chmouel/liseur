package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.chmouel.liseur.data.remote.UnreadableCredentialException
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.APP_BACKUP_TYPES
import com.chmouel.liseur.data.settings.AutoScrollPreference
import com.chmouel.liseur.data.settings.BackupValueType
import com.chmouel.liseur.data.settings.ImportResult as FontImportResult
import com.chmouel.liseur.data.settings.READER_BACKUP_TYPES
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.data.settings.TapSideWidth
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.data.settings.TypographyRange
import com.chmouel.liseur.data.settings.UserFontImport
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.VolumeKeyMode
import com.chmouel.liseur.data.settings.applyBackupJson
import com.chmouel.liseur.data.settings.fonts.UserFont
import com.chmouel.liseur.data.settings.validateBackupJson
import com.chmouel.liseur.domain.BackedUpBookFile
import com.chmouel.liseur.domain.BackedUpBookSessions
import com.chmouel.liseur.domain.BackedUpReadingPosition
import com.chmouel.liseur.domain.BackedUpTranslation
import com.chmouel.liseur.domain.BackupContents
import com.chmouel.liseur.domain.BookRestorePlan
import com.chmouel.liseur.domain.CompanionBackupContents
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.LibraryFilters
import com.chmouel.liseur.domain.decodeAnnotationBackup
import com.chmouel.liseur.domain.decodeBookBackup
import com.chmouel.liseur.domain.decodeCompanionBackup
import com.chmouel.liseur.domain.decodeReadingPositionBackup
import com.chmouel.liseur.domain.decodeSessionBackup
import com.chmouel.liseur.domain.decodeTranslationBackup
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.data.remote.ServerKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SettingsBackupFailure {
    FILE_ACCESS,
    INVALID_ARCHIVE,
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    STORAGE,
    RESTORE,
    PASSWORD,
    /** A saved account credential can no longer be read on this device. */
    UNREADABLE_CREDENTIAL,
}

data class SettingsBackupProgress(
    val phase: Phase,
    val done: Long,
    val total: Long?,
) {
    enum class Phase {
        HASHING_BOOKS,
        WRITING,
        READING,
        IMPORTING_BOOKS,
    }
}

internal data class BookArchiveLimits(
    val maxBookBytes: Long = SettingsBackupRepository.MAX_BOOK_BYTES,
    val maxBooksTotalBytes: Long = SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES,
    val usableSpace: (File) -> Long = { it.usableSpace },
)

data class SettingsBackupPreview(
    val settingCount: Int,
    val fontCount: Int,
    val annotationCount: Int = 0,
    val annotationBookCount: Int = 0,
    val matchedAnnotationBooks: Int = 0,
    val positionCount: Int = 0,
    val companionRecapCount: Int = 0,
    val includesConnections: Boolean = false,
    val translationBookCount: Int = 0,
    val bookCount: Int = 0,
    val booksInLibrary: Int = 0,
    val booksToImport: Int = 0,
    val bookImportBytes: Long = 0,
    val sessionCount: Int = 0,
)

sealed interface SettingsBackupInspection {
    data class Ready(val preview: SettingsBackupPreview, val archiveId: String) : SettingsBackupInspection
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupInspection
}

sealed interface SettingsBackupExportResult {
    data class Exported(
        val fonts: Int,
        val annotations: Int = 0,
        val liseurSyncNeedsPairing: Boolean = false,
        val translationBooks: Int = 0,
        val translationBooksSkipped: Int = 0,
        val books: Int = 0,
        val booksSkipped: Int = 0,
        val sessions: Int = 0,
        val bookBytes: Long = 0,
    ) : SettingsBackupExportResult
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupExportResult
}

/** A prepared archive waiting in app-private cache; book bytes join it only at save time. */
data class PreparedExport(
    val file: File,
    val fonts: Int,
    val annotations: Int = 0,
    val liseurSyncNeedsPairing: Boolean = false,
    val translationBooks: Int = 0,
    val translationBooksSkipped: Int = 0,
    val books: Int = 0,
    val booksSkipped: Int = 0,
    val sessions: Int = 0,
    val bookBytes: Long = 0,
)

sealed interface SettingsBackupPreparation {
    data class Prepared(val export: PreparedExport) : SettingsBackupPreparation
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupPreparation
}

sealed interface SettingsBackupRestoreResult {
    data class Restored(
        val fontsImported: Int,
        val fontsAlreadyPresent: Int,
        val fontFailures: Int,
        val annotationsAdded: Int = 0,
        val annotationsAlreadyPresent: Int = 0,
        val positionsRestored: Int = 0,
        val companionRecapsAdded: Int = 0,
        val liseurSyncNeedsPairing: Boolean = false,
        val translationBooks: Int = 0,
        val translationBooksSkipped: Int = 0,
        val booksImported: Int = 0,
        val booksAlreadyPresent: Int = 0,
        val bookFailures: Int = 0,
        val sessionsAdded: Int = 0,
        val sessionsAlreadyPresent: Int = 0,
    ) : SettingsBackupRestoreResult

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupRestoreResult
    data class PartiallyRestored(val liseurSyncNeedsPairing: Boolean = false) : SettingsBackupRestoreResult
}

/** Versioned archive for reading data, settings, fonts, and optional encrypted connections. */
class SettingsBackupRepository internal constructor(
    private val context: Context,
    private val appSettings: AppSettingsRepository,
    private val readerPreferences: ReaderPreferencesRepository,
    private val userFonts: UserFontRepository,
    private val annotations: AnnotationBackupRepository,
    private val positions: ReadingPositionBackupRepository,
    private val companion: CompanionBackupRepository,
    /** Writes the app settings of an archive; the app also ends what used a server the archive replaces. */
    private val restoreAppSettings: suspend (JSONObject) -> Unit = { appSettings.restoreBackupValues(it) },
    private val exportConnections: suspend () -> JSONObject = { error("Connection backup unavailable") },
    private val prepareConnectionRestore: (JSONObject, JSONObject) -> (suspend () -> Unit) = { _, _ -> error("Connection restore unavailable") },
    private val translations: TranslationBackupRepository? = null,
    private val sessions: ReadingSessionBackupRepository? = null,
    private val books: BookBackupRepository? = null,
    internal val bookLimits: BookArchiveLimits = BookArchiveLimits(),
) {
    constructor(
        context: Context,
        appSettings: AppSettingsRepository,
        readerPreferences: ReaderPreferencesRepository,
        userFonts: UserFontRepository,
        annotations: AnnotationBackupRepository,
        positions: ReadingPositionBackupRepository,
        companion: CompanionBackupRepository,
        restoreAppSettings: suspend (JSONObject) -> Unit = { appSettings.restoreBackupValues(it) },
        exportConnections: suspend () -> JSONObject = { error("Connection backup unavailable") },
        prepareConnectionRestore: (JSONObject, JSONObject) -> (suspend () -> Unit) = { _, _ -> error("Connection restore unavailable") },
        translations: TranslationBackupRepository? = null,
        sessions: ReadingSessionBackupRepository? = null,
        books: BookBackupRepository? = null,
    ) : this(
        context = context,
        appSettings = appSettings,
        readerPreferences = readerPreferences,
        userFonts = userFonts,
        annotations = annotations,
        positions = positions,
        companion = companion,
        restoreAppSettings = restoreAppSettings,
        exportConnections = exportConnections,
        prepareConnectionRestore = prepareConnectionRestore,
        translations = translations,
        sessions = sessions,
        books = books,
        bookLimits = BookArchiveLimits(),
    )
    private val operationMutex = Mutex()

    suspend fun bookCandidates(): List<BookBackupCandidate> =
        books?.candidates() ?: emptyList()

    suspend fun exportTo(
        target: Uri,
        password: CharArray? = null,
        includeTranslations: Boolean = true,
        includeSessions: Boolean = false,
        bookUrls: Set<String> = emptySet(),
        onProgress: (SettingsBackupProgress) -> Unit = {},
    ): SettingsBackupExportResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            when (val preparation = prepareExportLocked(password, includeTranslations, includeSessions, bookUrls, onProgress)) {
                is SettingsBackupPreparation.Failed ->
                    SettingsBackupExportResult.Failed(preparation.failure)
                is SettingsBackupPreparation.Prepared -> try {
                    writePreparedExportLocked(preparation.export, target, onProgress)
                } finally {
                    preparation.export.file.delete()
                }
            }
        }
    }

    /** Builds the archive in cache so the document picker never creates an empty destination. */
    suspend fun prepareExport(
        password: CharArray? = null,
        includeTranslations: Boolean = true,
        includeSessions: Boolean = false,
        bookUrls: Set<String> = emptySet(),
        onProgress: (SettingsBackupProgress) -> Unit = {},
    ): SettingsBackupPreparation {
        var prepared: PreparedExport? = null
        try {
            return withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    prepareExportLocked(password, includeTranslations, includeSessions, bookUrls, onProgress).also {
                        if (it is SettingsBackupPreparation.Prepared) prepared = it.export
                    }
                }
            }
        } catch (e: CancellationException) {
            // A caller cancelled during the handoff never learns of the archive, so it goes now.
            prepared?.let { discardPreparedExport(it) }
            throw e
        }
    }

    /** Copies a prepared archive to the user's document, removing it when the copy fails. */
    suspend fun writeExport(
        prepared: PreparedExport,
        target: Uri,
        onProgress: (SettingsBackupProgress) -> Unit = {},
    ): SettingsBackupExportResult {
        var started = false
        try {
            return withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    started = true
                    try {
                        writePreparedExportLocked(prepared, target, onProgress)
                    } finally {
                        prepared.file.delete()
                    }
                }
            }
        } catch (e: CancellationException) {
            // Cancelled before the copy began, the picker's new document is still empty.
            if (!started) discardExport(target)
            throw e
        }
    }

    /** Resolves a prepared archive name kept across recreation, refusing any file this app did not generate. */
    fun preparedExportFile(name: String): File? =
        name.takeIf { PREPARED_EXPORT_FILE.matches(it) }?.let { File(context.cacheDir, it) }

    /** Deletes a prepared archive the picker never consumed; safe to call twice. */
    suspend fun discardPreparedExport(prepared: PreparedExport) {
        withContext(NonCancellable + Dispatchers.IO) { prepared.file.delete() }
    }

    private suspend fun prepareExportLocked(
        password: CharArray?,
        includeTranslations: Boolean,
        includeSessions: Boolean,
        bookUrls: Set<String>,
        onProgress: (SettingsBackupProgress) -> Unit,
    ): SettingsBackupPreparation {
        sweepStaleExports()
        var completed = false
        var archiveFile: File? = null
        var annotationFile: File? = null
        var positionFile: File? = null
        var companionFile: File? = null
        var translationFile: File? = null
        var sessionFile: File? = null
        var booksIndexFile: File? = null
        try {
            val settings = JSONObject()
                .put("app", appSettings.backupValues())
                .put("reader", readerPreferences.backupValues())
            if (password != null && !settings.getJSONObject("app").has(ServerSettings.SERVERS_NAME)) {
                settings.getJSONObject("app").put(ServerSettings.SERVERS_NAME, "[]")
            }
            canonicalizeBackupSettings(settings)
            var liseurSyncNeedsPairing = false
            val connectionBytes = password?.let {
                val payload = try {
                    exportConnections()
                } catch (e: UnreadableCredentialException) {
                    throw BackupFailureException(SettingsBackupFailure.UNREADABLE_CREDENTIAL)
                }
                ConnectionBackupRepository.validate(payload, settings.getJSONObject("app"))
                liseurSyncNeedsPairing =
                    payload.optJSONObject("account")?.optString("kind") == ServerKind.LISEUR_SYNC.name
                val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
                try { BackupEncryption.encrypt(plaintext, it) } finally { plaintext.fill(0) }
            }
            val settingsBytes = settings.toString().toByteArray(Charsets.UTF_8)
            if (settingsBytes.size > MAX_SETTINGS_BYTES) {
                return SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val stagedAnnotations = File.createTempFile("annotations-export-", ".json", context.cacheDir)
            annotationFile = stagedAnnotations
            val annotationCount = try {
                annotations.writeContents(stagedAnnotations, MAX_ANNOTATIONS_BYTES)
            } catch (e: AnnotationBackupTooLarge) {
                throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
            }
            val annotationMetadata = hashFile(ANNOTATIONS_PATH, stagedAnnotations)

            val stagedPositions = File.createTempFile("positions-export-", ".json", context.cacheDir)
            positionFile = stagedPositions
            try {
                positions.writeContents(stagedPositions, MAX_READING_DATA_BYTES - annotationMetadata.size)
            } catch (e: ReadingPositionBackupTooLarge) {
                throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
            }
            val positionMetadata = hashFile(POSITIONS_PATH, stagedPositions)

            val stagedCompanion = File.createTempFile("companion-export-", ".json", context.cacheDir)
            companionFile = stagedCompanion
            companion.writeContents(stagedCompanion, MAX_COMPANION_BYTES)
            val companionMetadata = hashFile(COMPANION_PATH, stagedCompanion)

            var translationResult = TranslationBackupResult()
            val translationMetadata = if (includeTranslations && translations != null) {
                val staged = File.createTempFile("translations-export-", ".json", context.cacheDir)
                translationFile = staged
                translationResult = translations.writeContents(staged, TranslationBackupRepository.MAX_BYTES)
                hashFile(TRANSLATIONS_PATH, staged)
            } else null

            var sessionCount = 0
            val sessionMetadata = if (includeSessions && sessions != null) {
                val staged = File.createTempFile("sessions-export-", ".json", context.cacheDir)
                sessionFile = staged
                sessionCount = try {
                    sessions.writeContents(staged, MAX_SESSIONS_BYTES)
                } catch (e: ReadingSessionBackupTooLarge) {
                    throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
                }
                hashFile(SESSIONS_PATH, staged)
            } else null

            var preparedBooks = PreparedBooksResult()
            val booksIndexMetadata = if (bookUrls.isNotEmpty() && books != null) {
                val staged = File.createTempFile("books-export-", ".json", context.cacheDir)
                booksIndexFile = staged
                preparedBooks = try {
                    books.prepareContents(
                        selectedUrls = bookUrls,
                        file = staged,
                        maxIndexBytes = MAX_BOOK_INDEX_BYTES,
                        maxBookBytes = bookLimits.maxBookBytes,
                        maxBooksTotalBytes = bookLimits.maxBooksTotalBytes,
                        maxBookFiles = MAX_BOOK_FILES,
                    ) { done, total ->
                        onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.HASHING_BOOKS, done, total))
                    }
                } catch (e: BookBackupTooLarge) {
                    throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
                }
                if (preparedBooks.books > 0) hashFile(BOOKS_PATH, staged) else null
            } else null

            val fonts = userFonts.backupFonts()
            if (fonts.size > MAX_FONT_FILES) {
                return SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val listed = ArrayList<EntryMetadata>(fonts.size + preparedBooks.entries.size + 8)
            listed += EntryMetadata(SETTINGS_PATH, settingsBytes.size.toLong(), sha256(settingsBytes))
            listed += annotationMetadata
            listed += positionMetadata
            listed += companionMetadata
            translationMetadata?.let { listed += it }
            connectionBytes?.let { listed += EntryMetadata(CONNECTIONS_PATH, it.size.toLong(), sha256(it)) }
            sessionMetadata?.let { listed += it }
            booksIndexMetadata?.let { listed += it }

            var totalBytes = settingsBytes.size.toLong() +
                annotationMetadata.size +
                positionMetadata.size +
                companionMetadata.size +
                (connectionBytes?.size ?: 0) +
                (translationMetadata?.size ?: 0) +
                (sessionMetadata?.size ?: 0) +
                (booksIndexMetadata?.size ?: 0)

            val fontMetadata = ArrayList<EntryMetadata>(fonts.size)
            val fontNames = fonts.associate { "fonts/${it.fileName}" to it.displayName }
            for (font in fonts) {
                currentCoroutineContext().ensureActive()
                val path = "fonts/${font.fileName}"
                if (!safePath(path)) {
                    return SettingsBackupPreparation.Failed(
                        SettingsBackupFailure.INVALID_ARCHIVE,
                    )
                }
                val metadata = hashFile(path, font.file)
                totalBytes += metadata.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    return SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE)
                }
                fontMetadata += metadata
                listed += metadata
            }

            for (bookEntry in preparedBooks.entries) {
                listed += EntryMetadata(bookEntry.path, bookEntry.size, bookEntry.sha256)
            }

            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("application", "liseur")
                .put(
                    "entries",
                    JSONArray().also { entries ->
                        listed.forEach { entry ->
                            val value = JSONObject()
                                .put("path", entry.path)
                                .put("size", entry.size)
                                .put("sha256", entry.sha256)
                            fontNames[entry.path]?.let { value.put("displayName", it) }
                            entries.put(value)
                        }
                    },
                )
            val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
            totalBytes += manifestBytes.size
            if (manifestBytes.size > MAX_MANIFEST_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                return SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val archive = File.createTempFile("settings-export-", ".zip", context.cacheDir)
            archiveFile = archive
            ZipOutputStream(archive.outputStream()).use { zip ->
                writeEntry(zip, MANIFEST_PATH, manifestBytes)
                writeEntry(zip, SETTINGS_PATH, settingsBytes)
                writeFileEntry(zip, ANNOTATIONS_PATH, stagedAnnotations)
                writeFileEntry(zip, POSITIONS_PATH, stagedPositions)
                writeFileEntry(zip, COMPANION_PATH, stagedCompanion)
                translationFile?.let { writeFileEntry(zip, TRANSLATIONS_PATH, it) }
                connectionBytes?.let { writeEntry(zip, CONNECTIONS_PATH, it) }
                sessionFile?.let { writeFileEntry(zip, SESSIONS_PATH, it) }
                if (booksIndexMetadata != null && booksIndexFile != null) {
                    writeFileEntry(zip, BOOKS_PATH, booksIndexFile)
                }
                fonts.zip(fontMetadata).forEach { (font, expected) ->
                    currentCoroutineContext().ensureActive()
                    val actual = writeFileEntry(zip, expected.path, font.file)
                    if (actual != expected) {
                        throw BackupFailureException(SettingsBackupFailure.FILE_ACCESS)
                    }
                }
            }
            completed = true
            return SettingsBackupPreparation.Prepared(
                PreparedExport(
                    file = archive,
                    fonts = fonts.size,
                    annotations = annotationCount,
                    liseurSyncNeedsPairing = liseurSyncNeedsPairing,
                    translationBooks = translationResult.books,
                    translationBooksSkipped = translationResult.skipped,
                    books = preparedBooks.books,
                    booksSkipped = preparedBooks.skipped,
                    sessions = sessionCount,
                    bookBytes = preparedBooks.bookBytes,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFailureException) {
            e.cause?.let { Log.w(TAG, "Settings export failed", it) }
            return SettingsBackupPreparation.Failed(e.failure)
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the prepared settings backup", e)
            return SettingsBackupPreparation.Failed(SettingsBackupFailure.STORAGE)
        } catch (e: Exception) {
            Log.w(TAG, "Settings export failed", e)
            return SettingsBackupPreparation.Failed(SettingsBackupFailure.FILE_ACCESS)
        } finally {
            annotationFile?.delete()
            positionFile?.delete()
            companionFile?.delete()
            translationFile?.delete()
            sessionFile?.delete()
            booksIndexFile?.delete()
            if (!completed) archiveFile?.delete()
        }
    }

    private suspend fun writePreparedExportLocked(
        prepared: PreparedExport,
        target: Uri,
        onProgress: (SettingsBackupProgress) -> Unit,
    ): SettingsBackupExportResult {
        var completed = false
        try {
            val booksJsonText = readBooksJsonFromPreparedZip(prepared.file)
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: return SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
            if (booksJsonText == null) {
                val totalBytes = prepared.file.length().takeIf { it > 0 }
                var writtenBytes = 0L
                output.use { sink ->
                    prepared.file.inputStream().use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            writtenBytes += count
                            onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.WRITING, writtenBytes, totalBytes))
                        }
                    }
                }
                completed = true
                return SettingsBackupExportResult.Exported(
                    fonts = prepared.fonts,
                    annotations = prepared.annotations,
                    liseurSyncNeedsPairing = prepared.liseurSyncNeedsPairing,
                    translationBooks = prepared.translationBooks,
                    translationBooksSkipped = prepared.translationBooksSkipped,
                    books = prepared.books,
                    booksSkipped = prepared.booksSkipped,
                    sessions = prepared.sessions,
                    bookBytes = prepared.bookBytes,
                )
            } else {
                val bookBackup = books ?: return SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
                val records = decodeBookBackup(booksJsonText)
                val distinctBookBytes = records.distinctBy { it.sha256 }.sumOf { it.size }
                val totalExpectedBytes = prepared.file.length() + distinctBookBytes
                var writtenBytes = 0L
                onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.WRITING, 0L, totalExpectedBytes))
                ZipOutputStream(output.buffered()).use { zip ->
                    ZipInputStream(prepared.file.inputStream().buffered()).use { sourceZip ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = sourceZip.nextEntry ?: break
                            zip.putNextEntry(ZipEntry(entry.name))
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = sourceZip.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                writtenBytes += count
                                onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.WRITING, writtenBytes, totalExpectedBytes))
                            }
                            zip.closeEntry()
                            sourceZip.closeEntry()
                        }
                    }
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    bookBackup.streamBooksToZip(records, zip) { delta ->
                        writtenBytes += delta
                        onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.WRITING, writtenBytes, totalExpectedBytes))
                    }
                }
                completed = true
                return SettingsBackupExportResult.Exported(
                    fonts = prepared.fonts,
                    annotations = prepared.annotations,
                    liseurSyncNeedsPairing = prepared.liseurSyncNeedsPairing,
                    translationBooks = prepared.translationBooks,
                    translationBooksSkipped = prepared.translationBooksSkipped,
                    books = records.size,
                    booksSkipped = prepared.booksSkipped,
                    sessions = prepared.sessions,
                    bookBytes = distinctBookBytes,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Settings export failed", e)
            return SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
        } finally {
            if (!completed) discardExport(target)
        }
    }

    private fun readBooksJsonFromPreparedZip(file: File): String? {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == BOOKS_PATH) {
                    val bytes = zip.readBytes()
                    if (bytes.size > MAX_BOOK_INDEX_BYTES) throw IOException("Prepared books.json exceeds limit")
                    return bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
    }

    /** Removes a newly created ZIP document when its export was abandoned. */
    suspend fun discardExport(target: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            if (target.scheme == "content") {
                DocumentsContract.deleteDocument(context.contentResolver, target)
            }
        }.onFailure { Log.w(TAG, "Could not remove incomplete backup", it) }
        Unit
    }

    suspend fun inspect(
        source: Uri,
        onProgress: (SettingsBackupProgress) -> Unit = {},
    ): SettingsBackupInspection = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            sweepStaleStaging()
            when (val result = readArchive(source, onProgress)) {
                is ArchiveResult.Error -> SettingsBackupInspection.Failed(result.failure)
                is ArchiveResult.Valid -> {
                    var retained = false
                    try {
                        val settings = decodeSettings(result.entries.getValue(SETTINGS_PATH).file)
                        validateSettings(settings)
                        val bookFiles = decodeBooks(result.entries)
                        val bookPlan = if (bookFiles.isNotEmpty() && books != null) {
                            books.planRestore(bookFiles)
                        } else {
                            BookRestorePlan(emptyMap(), emptyList())
                        }
                        val previewAliases = buildMap {
                            putAll(bookPlan.matched)
                            for (missing in bookPlan.missing) {
                                put(missing.bookId, missing.bookId)
                            }
                        }
                        val annotationPreview = annotations.previewContents(decodeAnnotations(result.entries), previewAliases)
                        val positionContents = decodePositions(result.entries)
                        val companionContents = decodeCompanion(result.entries)
                        val translationContents = decodeTranslations(result.entries)
                        val sessionContents = decodeSessions(result.entries)
                        result.entries[CONNECTIONS_PATH]?.let {
                            BackupEncryption.validate(readStagedBytes(it.file, BackupEncryption.MAX_BYTES))
                        }
                        val index = JSONObject().put(
                            "entries",
                            JSONArray().also { values ->
                                result.entries.forEach { (path, entry) ->
                                    val value = JSONObject().put("path", path).put("file", entry.file.name)
                                    entry.displayName?.let { value.put("displayName", it) }
                                    values.put(value)
                                }
                            },
                        )
                        try {
                            File(result.stagingDirectory, INDEX_PATH).writeText(index.toString())
                        } catch (e: IOException) {
                            throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
                        }
                        val distinctMissingStaged = bookPlan.missing
                            .distinctBy { it.sha256 }
                            .filter { "books/${it.sha256}.epub" in result.entries }
                        retained = true
                        SettingsBackupInspection.Ready(
                            SettingsBackupPreview(
                                settingCount = countKnownSettings(settings),
                                fontCount = result.entries.keys.count { it.startsWith("fonts/") },
                                annotationCount = annotationPreview.marks,
                                annotationBookCount = annotationPreview.books,
                                matchedAnnotationBooks = annotationPreview.matchedBooks,
                                positionCount = positionContents.size,
                                companionRecapCount = companionContents.recaps.size,
                                includesConnections = CONNECTIONS_PATH in result.entries,
                                translationBookCount = translationContents.size,
                                bookCount = bookFiles.size,
                                booksInLibrary = bookPlan.matched.size,
                                booksToImport = distinctMissingStaged.size,
                                bookImportBytes = distinctMissingStaged.sumOf { it.size },
                                sessionCount = sessionContents.sumOf { it.sessions.size },
                            ),
                            result.stagingDirectory.name.removePrefix(PROCESS_STAGING_PREFIX),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BackupFailureException) {
                        SettingsBackupInspection.Failed(e.failure)
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not save settings backup inspection", e)
                        SettingsBackupInspection.Failed(SettingsBackupFailure.STORAGE)
                    } catch (e: Exception) {
                        SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE)
                    } finally {
                        if (!retained) removeStaging(result.stagingDirectory)
                    }
                }
            }
        }
    }

    suspend fun restore(
        archiveId: String,
        password: CharArray? = null,
        onProgress: (SettingsBackupProgress) -> Unit = {},
    ): SettingsBackupRestoreResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val archive = readStagedArchive(archiveId)
                ?: return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE)
            var retainForRetry = false
            try {
                val settings = try {
                    decodeSettings(archive.entries.getValue(SETTINGS_PATH).file).also(::validateSettings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                } catch (e: Exception) {
                    return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE)
                }

                val annotationContents = try {
                    decodeAnnotations(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val positionContents = try {
                    decodePositions(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val companionContents = try {
                    decodeCompanion(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val translationContents = try {
                    decodeTranslations(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val sessionContents = try {
                    decodeSessions(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val bookContents = try {
                    decodeBooks(archive.entries)
                } catch (e: BackupFailureException) {
                    return@withContext SettingsBackupRestoreResult.Failed(e.failure)
                }

                val connections = archive.entries[CONNECTIONS_PATH]?.let { entry ->
                    val plaintext = try {
                        if (password == null) throw IllegalArgumentException("Password required")
                        BackupEncryption.decrypt(readStagedBytes(entry.file, BackupEncryption.MAX_BYTES), password)
                    } catch (e: Exception) {
                        retainForRetry = true
                        return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.PASSWORD)
                    }
                    try {
                        JSONObject(plaintext.toString(Charsets.UTF_8)).also {
                            ConnectionBackupRepository.validate(it, settings.getJSONObject("app"))
                        }
                    } catch (e: Exception) {
                        return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE)
                    } finally { plaintext.fill(0) }
                }
                val liseurSyncNeedsPairing =
                    connections?.optJSONObject("account")?.optString("kind") == ServerKind.LISEUR_SYNC.name

                val applyConnections = try {
                    connections?.let { prepareConnectionRestore(it, settings.getJSONObject("app")) }
                } catch (e: Exception) {
                    return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.RESTORE)
                }
                var appSettingsRestored = false
                try {
                    // Once a connection restore begins, any failure may follow durable writes.
                    if (applyConnections != null) {
                        appSettingsRestored = true
                        applyConnections()
                    } else {
                        restoreAppSettings(settings.getJSONObject("app"))
                    }
                    appSettingsRestored = true
                    readerPreferences.restoreBackupValues(settings.getJSONObject("reader"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Settings restore failed", e)
                    return@withContext if (appSettingsRestored) {
                        SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                    } else {
                        SettingsBackupRestoreResult.Failed(SettingsBackupFailure.RESTORE)
                    }
                }

                var imported = 0
                var present = 0
                var failures = 0
                try {
                    val fontImports = archive.entries
                        .filterKeys { it.startsWith("fonts/") }
                        .map { (path, entry) ->
                            currentCoroutineContext().ensureActive()
                            val name = path.removePrefix("fonts/")
                            val pickedName = entry.displayName?.let {
                                "$it.${name.substringAfterLast('.', "")}"
                            } ?: name
                            UserFontImport(Uri.fromFile(entry.file), pickedName)
                        }
                    userFonts.importAll(fontImports).forEach { result ->
                        when (result) {
                            is FontImportResult.Imported -> imported++
                            is FontImportResult.AlreadyPresent -> present++
                            else -> failures++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Font restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val bookOutcome = try {
                    if (bookContents.isEmpty()) {
                        BookRestoreOutcome()
                    } else if (books != null) {
                        val stagedBookFiles = archive.entries
                            .filterKeys { it.startsWith("books/") }
                            .mapValues { (_, entry) -> entry.file }
                        books.restore(bookContents, stagedBookFiles) { done, total ->
                            onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.IMPORTING_BOOKS, done, total))
                        }
                    } else {
                        BookRestoreOutcome(failures = bookContents.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Book restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val aliases = bookOutcome.aliases

                val annotationResult = try {
                    annotations.importContents(annotationContents, aliases)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Annotation restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val restoredPositions = try {
                    positions.restore(positionContents, aliases)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Reading position restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val sessionResult = try {
                    if (sessionContents.isEmpty()) {
                        SessionRestoreResult()
                    } else {
                        sessions?.restore(sessionContents, aliases) ?: SessionRestoreResult()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Reading session restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val companionRecapsAdded = try {
                    if (companionContents.isEmpty()) 0 else companion.restore(companionContents, aliases)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Companion restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                val translationResult = try {
                    if (translationContents.isEmpty()) TranslationBackupResult()
                    else translations?.restore(translationContents, aliases) ?: TranslationBackupResult(0, translationContents.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Translation restore stopped after settings were restored", e)
                    return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
                }

                SettingsBackupRestoreResult.Restored(
                    fontsImported = imported,
                    fontsAlreadyPresent = present,
                    fontFailures = failures,
                    annotationsAdded = annotationResult.added,
                    annotationsAlreadyPresent = annotationResult.alreadyHere,
                    positionsRestored = restoredPositions,
                    companionRecapsAdded = companionRecapsAdded,
                    liseurSyncNeedsPairing = liseurSyncNeedsPairing,
                    translationBooks = translationResult.books,
                    translationBooksSkipped = translationResult.skipped,
                    booksImported = bookOutcome.imported,
                    booksAlreadyPresent = bookOutcome.alreadyPresent,
                    bookFailures = bookOutcome.failures,
                    sessionsAdded = sessionResult.added,
                    sessionsAlreadyPresent = sessionResult.alreadyPresent,
                )
            } finally {
                if (!retainForRetry) removeStaging(archive.stagingDirectory)
            }
        }
    }

    suspend fun discardInspection(archiveId: String?) = withContext(Dispatchers.IO) {
        if (archiveId == null || !ARCHIVE_ID.matches(archiveId)) return@withContext
        operationMutex.withLock { removeStaging(stagingDirectory(archiveId)) }
    }

    suspend fun discardProcessInspections() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            context.cacheDir.listFiles()
                ?.filter {
                    it.isDirectory && it.name.startsWith(PROCESS_STAGING_PREFIX) &&
                        STAGING_DIRECTORY.matches(it.name)
                }
                ?.forEach(::removeStaging)
            sweepStaleExports()
        }
    }

    private fun readStagedArchive(archiveId: String): ArchiveResult.Valid? {
        if (!ARCHIVE_ID.matches(archiveId)) return null
        val directory = stagingDirectory(archiveId)
        val index = try {
            JSONObject(File(directory, INDEX_PATH).readText())
        } catch (e: Exception) {
            Log.w(TAG, "Could not read inspected backup", e)
            return null
        }
        val listed = index.optJSONArray("entries") ?: return null
        val entries = linkedMapOf<String, StagedEntry>()
        for (position in 0 until listed.length()) {
            val item = listed.optJSONObject(position) ?: return null
            val path = item.optString("path")
            val fileName = item.optString("file")
            if (!safePath(path) || path == MANIFEST_PATH || !fileName.matches(ENTRY_FILE)) return null
            val displayName = item.opt("displayName") as? String
            if (
                (item.has("displayName") && displayName == null) ||
                (displayName != null &&
                    (!path.startsWith("fonts/") ||
                        com.chmouel.liseur.data.settings.fonts.FontNames.sanitize(displayName) != displayName))
            ) {
                return null
            }
            val file = File(directory, fileName)
            if (!file.isFile) return null
            entries[path] = StagedEntry(file, file.length(), "", displayName)
        }
        if (
            SETTINGS_PATH !in entries ||
            entries.keys.any {
                it !in setOf(
                    SETTINGS_PATH,
                    ANNOTATIONS_PATH,
                    POSITIONS_PATH,
                    COMPANION_PATH,
                    CONNECTIONS_PATH,
                    TRANSLATIONS_PATH,
                    SESSIONS_PATH,
                    BOOKS_PATH,
                ) && !it.startsWith("fonts/") && !BOOK_ENTRY_PATH.matches(it)
            }
        ) {
            return null
        }
        return ArchiveResult.Valid(directory, entries)
    }

    private suspend fun readArchive(
        source: Uri,
        onProgress: (SettingsBackupProgress) -> Unit,
    ): ArchiveResult {
        val staging = try {
            File(context.cacheDir, "$PROCESS_STAGING_PREFIX${UUID.randomUUID()}").also {
                if (!it.mkdirs()) throw IOException("Could not create staging directory")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not prepare backup staging", e)
            return ArchiveResult.Error(SettingsBackupFailure.STORAGE)
        }
        var keepStaging = false
        return try {
            val input = try {
                context.contentResolver.openInputStream(source)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not open settings backup", e)
                return ArchiveResult.Error(SettingsBackupFailure.FILE_ACCESS)
            } ?: return ArchiveResult.Error(SettingsBackupFailure.FILE_ACCESS)

            val buffered = input.buffered()
            val firstByte = try {
                buffered.mark(1)
                buffered.read().also { buffered.reset() }
            } catch (e: Exception) {
                buffered.close()
                throw e
            }
            if (firstByte != 'P'.code) {
                // Old annotation-only JSON backups use the same preview and restore flow.
                val annotationEntry = buffered.use {
                    stageEntry(it, File(staging, "entry-0"), MAX_ANNOTATIONS_BYTES)
                }
                val settingsFile = try {
                    File(staging, "entry-1").apply {
                        writeText(JSONObject().put("app", JSONObject()).put("reader", JSONObject()).toString())
                    }
                } catch (e: IOException) {
                    throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
                }
                keepStaging = true
                return ArchiveResult.Valid(
                    staging,
                    mapOf(
                        ANNOTATIONS_PATH to annotationEntry,
                        SETTINGS_PATH to StagedEntry(settingsFile, settingsFile.length(), ""),
                    ),
                )
            }

            val entries = linkedMapOf<String, StagedEntry>()
            val hashedOnlyEntries = linkedMapOf<String, StagedEntry>()
            var totalBytes = 0L
            var readingDataBytes = 0L
            var bookBytes = 0L
            var totalReadProgressBytes = 0L
            var cachedBooksPlan: Pair<Map<String, List<BackedUpBookFile>>, Set<String>>? = null

            ZipInputStream(buffered).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    val isBookEntry = path.startsWith("books/")
                    val isKnownNonBook = path in setOf(
                        MANIFEST_PATH,
                        SETTINGS_PATH,
                        ANNOTATIONS_PATH,
                        POSITIONS_PATH,
                        COMPANION_PATH,
                        CONNECTIONS_PATH,
                        TRANSLATIONS_PATH,
                        SESSIONS_PATH,
                        BOOKS_PATH,
                    ) || path.startsWith("fonts/")
                    if (
                        entry.isDirectory ||
                        !safePath(path) ||
                        (!isKnownNonBook && !BOOK_ENTRY_PATH.matches(path)) ||
                        path in entries ||
                        path in hashedOnlyEntries ||
                        (entries.size + hashedOnlyEntries.size) >= MAX_ARCHIVE_ENTRIES
                    ) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }

                    if (isBookEntry) {
                        if (BOOKS_PATH !in entries) {
                            return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                        }
                        val (recordsBySha, missingShas) = cachedBooksPlan ?: run {
                            val decoded = decodeBooks(entries)
                            val plan = books?.planRestore(decoded)
                                ?: BookRestorePlan(emptyMap(), decoded)
                            val grouped = decoded.groupBy { it.sha256 }
                            val missing = plan.missing.mapTo(mutableSetOf()) { it.sha256 }
                            (grouped to missing).also { cachedBooksPlan = it }
                        }
                        val sha = path.removePrefix("books/").removeSuffix(".epub")
                        val recordsForSha = recordsBySha[sha]
                            ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                        val declaredSizes = recordsForSha.map { it.size }.distinct()
                        if (declaredSizes.size != 1) {
                            return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                        }
                        val declaredSize = declaredSizes.single()
                        val sizeLimit = minOf(bookLimits.maxBookBytes, bookLimits.maxBooksTotalBytes - bookBytes)
                        if (sizeLimit < 0 || declaredSize > sizeLimit) {
                            return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
                        }
                        if (sha in missingShas) {
                            if (bookLimits.usableSpace(staging) - declaredSize < CACHE_HEADROOM_BYTES) {
                                return ArchiveResult.Error(SettingsBackupFailure.STORAGE)
                            }
                            // Read no further than the declared size, so a book understating
                            // its size cannot exhaust the headroom this check just saved.
                            val staged = try {
                                stageEntry(
                                    input = zip,
                                    file = File(staging, "entry-${entries.size}"),
                                    sizeLimit = declaredSize,
                                ) { delta ->
                                    totalReadProgressBytes += delta
                                    onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.READING, totalReadProgressBytes, null))
                                }
                            } catch (e: BackupFailureException) {
                                if (e.failure == SettingsBackupFailure.TOO_LARGE) {
                                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                                }
                                throw e
                            }
                            if (staged.size != declaredSize) {
                                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                            }
                            bookBytes += staged.size
                            entries[path] = staged
                        } else {
                            val hashed = try {
                                hashEntry(
                                    input = zip,
                                    sizeLimit = declaredSize,
                                ) { delta ->
                                    totalReadProgressBytes += delta
                                    onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.READING, totalReadProgressBytes, null))
                                }
                            } catch (e: BackupFailureException) {
                                if (e.failure == SettingsBackupFailure.TOO_LARGE) {
                                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                                }
                                throw e
                            }
                            if (hashed.size != declaredSize) {
                                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                            }
                            bookBytes += hashed.size
                            hashedOnlyEntries[path] = hashed
                        }
                    } else {
                        val sizeLimit = minOf(entryLimit(path), MAX_TOTAL_BYTES - totalBytes)
                        val staged = stageEntry(
                            input = zip,
                            file = File(staging, "entry-${entries.size}"),
                            sizeLimit = sizeLimit,
                        ) { delta ->
                            totalReadProgressBytes += delta
                            onProgress(SettingsBackupProgress(SettingsBackupProgress.Phase.READING, totalReadProgressBytes, null))
                        }
                        if (path == ANNOTATIONS_PATH || path == POSITIONS_PATH) {
                            readingDataBytes += staged.size
                            if (readingDataBytes > MAX_READING_DATA_BYTES) {
                                return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
                            }
                        }
                        totalBytes += staged.size
                        entries[path] = staged
                    }
                    zip.closeEntry()
                }
            }

            val allSeenEntries = entries + hashedOnlyEntries

            val manifestEntry = entries[MANIFEST_PATH]
                ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            val settingsEntry = entries[SETTINGS_PATH]
                ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            if (manifestEntry.size > MAX_MANIFEST_BYTES || settingsEntry.size > MAX_SETTINGS_BYTES) {
                return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
            }
            if (entries.keys.count { it.startsWith("fonts/") } > MAX_FONT_FILES) {
                return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
            }
            val bookEntryPaths = allSeenEntries.keys.filter { it.startsWith("books/") }
            if (bookEntryPaths.size > MAX_BOOK_FILES) {
                return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
            }

            val manifest = try {
                JSONObject(readStagedBytes(manifestEntry.file, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFailureException) {
                return ArchiveResult.Error(e.failure)
            } catch (e: Exception) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (manifest.opt("application") != "liseur") {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (manifest.opt("format") !in setOf(1, 2, 3, 4, 5, 6, FORMAT)) {
                return ArchiveResult.Error(SettingsBackupFailure.UNSUPPORTED_VERSION)
            }
            val formatVersion = manifest.optInt("format")
            if ((formatVersion >= 2) != (ANNOTATIONS_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if ((formatVersion >= 3) != (POSITIONS_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if ((formatVersion >= 4) != (COMPANION_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (formatVersion < 5 && CONNECTIONS_PATH in entries) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (formatVersion < 6 && TRANSLATIONS_PATH in entries) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (formatVersion < 7 && (SESSIONS_PATH in entries || BOOKS_PATH in entries || bookEntryPaths.isNotEmpty())) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if ((BOOKS_PATH in entries) != bookEntryPaths.isNotEmpty()) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (BOOKS_PATH in entries) {
                val decodedBooks = decodeBooks(entries)
                val expectedBookPaths = decodedBooks.mapTo(mutableSetOf()) { "books/${it.sha256}.epub" }
                if (expectedBookPaths.isEmpty() || expectedBookPaths != bookEntryPaths.toSet()) {
                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                }
                for (record in decodedBooks) {
                    val bookEntry = allSeenEntries["books/${record.sha256}.epub"]
                        ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    if (record.size != bookEntry.size) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }
                }
            }

            val listed = manifest.optJSONArray("entries")
                ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            val expected = mutableMapOf<String, ExpectedEntry>()
            for (index in 0 until listed.length()) {
                val item = listed.optJSONObject(index)
                    ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                val path = item.opt("path") as? String
                    ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                val limit = entryLimit(path)
                val size = (item.opt("size") as? Number)?.toDouble()?.let { rawSize ->
                    if (
                        rawSize.isFinite() &&
                        rawSize % 1.0 == 0.0 &&
                        rawSize in 0.0..limit.toDouble()
                    ) {
                        rawSize.toLong()
                    } else {
                        -1L
                    }
                } ?: -1L
                val digest = item.opt("sha256") as? String
                    ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                val displayName = item.opt("displayName") as? String
                if (
                    (item.has("displayName") && displayName == null) ||
                    (displayName != null &&
                        (!path.startsWith("fonts/") ||
                            com.chmouel.liseur.data.settings.fonts.FontNames.sanitize(displayName) != displayName))
                ) {
                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                }
                if (
                    !safePath(path) ||
                    path == MANIFEST_PATH ||
                    size !in 0..limit ||
                    !SHA256.matches(digest) ||
                    expected.put(path, ExpectedEntry(size, digest, displayName)) != null
                ) {
                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                }
            }
            if (expected.keys != allSeenEntries.keys - MANIFEST_PATH) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            expected.forEach { (path, check) ->
                val entry = allSeenEntries.getValue(path)
                if (entry.size != check.size || entry.sha256 != check.sha256) {
                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                }
                if (path.startsWith("fonts/")) {
                    val name = path.removePrefix("fonts/")
                    val digest = name.substringBefore('.')
                    val extension = name.substringAfter('.', "")
                    if (UserFont.fileNameFor(digest, extension) != name || digest != entry.sha256) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }
                }
                if (path.startsWith("books/")) {
                    val expectedName = "${entry.sha256}.epub"
                    if (!BOOK_ENTRY_PATH.matches(path) || path.removePrefix("books/") != expectedName) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }
                }
            }
            keepStaging = true
            ArchiveResult.Valid(
                staging,
                (entries - MANIFEST_PATH).mapValues { (path, entry) ->
                    entry.copy(displayName = expected.getValue(path).displayName)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFailureException) {
            e.cause?.let { Log.w(TAG, "Could not stage settings backup", it) }
            ArchiveResult.Error(e.failure)
        } catch (e: Exception) {
            Log.w(TAG, "Settings backup is damaged or unreadable", e)
            ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
        } finally {
            if (!keepStaging) removeStaging(staging)
        }
    }

    private suspend fun stageEntry(
        input: InputStream,
        file: File,
        sizeLimit: Long,
        onBytes: (Long) -> Unit = {},
    ): StagedEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
                    }
                    if (count < 0) break
                    size += count
                    if (size > sizeLimit) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
                    try {
                        output.write(buffer, 0, count)
                    } catch (e: IOException) {
                        throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
                    }
                    digest.update(buffer, 0, count)
                    onBytes(count.toLong())
                }
            }
        } catch (e: BackupFailureException) {
            throw e
        } catch (e: IOException) {
            throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
        }
        return StagedEntry(file, size, digest.hexDigest())
    }

    private suspend fun hashEntry(
        input: InputStream,
        sizeLimit: Long,
        onBytes: (Long) -> Unit = {},
    ): StagedEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
            }
            if (count < 0) break
            size += count
            if (size > sizeLimit) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
            digest.update(buffer, 0, count)
            onBytes(count.toLong())
        }
        return StagedEntry(File(""), size, digest.hexDigest())
    }

    private fun decodeBooks(entries: Map<String, StagedEntry>): List<BackedUpBookFile> {
        val entry = entries[BOOKS_PATH] ?: return emptyList()
        val text = readStagedBytes(entry.file, MAX_BOOK_INDEX_BYTES).toString(Charsets.UTF_8)
        return try {
            decodeBookBackup(text)
        } catch (e: Exception) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }
    }

    private fun decodeSessions(entries: Map<String, StagedEntry>): List<BackedUpBookSessions> {
        val entry = entries[SESSIONS_PATH] ?: return emptyList()
        if (!entry.file.isFile || entry.file.length() > MAX_SESSIONS_BYTES) {
            throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
        }
        return try {
            entry.file.reader(Charsets.UTF_8).buffered().use(::decodeSessionBackup)
        } catch (e: Exception) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }
    }

    private fun decodeTranslations(entries: Map<String, StagedEntry>): List<BackedUpTranslation> {
        val entry = entries[TRANSLATIONS_PATH] ?: return emptyList()
        if (!entry.file.isFile || entry.file.length() > TranslationBackupRepository.MAX_BYTES) {
            throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
        }
        return try {
            entry.file.reader(Charsets.UTF_8).buffered().use(::decodeTranslationBackup)
        } catch (e: Exception) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }
    }

    private fun decodeCompanion(entries: Map<String, StagedEntry>): CompanionBackupContents {
        val entry = entries[COMPANION_PATH] ?: return CompanionBackupContents()
        val text = readStagedBytes(entry.file, MAX_COMPANION_BYTES).toString(Charsets.UTF_8)
        return try {
            decodeCompanionBackup(text)
        } catch (e: Exception) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }
    }

    private fun decodePositions(entries: Map<String, StagedEntry>): List<BackedUpReadingPosition> {
        val entry = entries[POSITIONS_PATH] ?: return emptyList()
        return try {
            decodeReadingPositionBackup(readStagedBytes(entry.file, MAX_POSITIONS_BYTES).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }
    }

    private fun decodeAnnotations(entries: Map<String, StagedEntry>): BackupContents.Readable {
        val entry = entries[ANNOTATIONS_PATH] ?: return BackupContents.Readable(emptyList())
        val text = readStagedBytes(entry.file, MAX_ANNOTATIONS_BYTES).toString(Charsets.UTF_8)
        return when (val contents = decodeAnnotationBackup(text)) {
            is BackupContents.Readable -> contents
            is BackupContents.Unreadable -> throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE)
        }
    }

    private fun decodeSettings(file: File): JSONObject =
        try {
            JSONObject(readStagedBytes(file, MAX_SETTINGS_BYTES).toString(Charsets.UTF_8))
        } catch (e: JSONException) {
            throw BackupFailureException(SettingsBackupFailure.INVALID_ARCHIVE, e)
        }

    private fun readStagedBytes(file: File, limit: Long): ByteArray {
        if (!file.isFile || file.length() > limit) {
            throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
        }
        return try {
            file.readBytes()
        } catch (e: IOException) {
            throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
        }
    }

    private fun validateSettings(settings: JSONObject) {
        val app = settings.optJSONObject("app")
            ?: throw IllegalArgumentException("Missing app settings")
        val reader = settings.optJSONObject("reader")
            ?: throw IllegalArgumentException("Missing reader settings")
        app.validateBackupJson(APP_BACKUP_TYPES)
        reader.validateBackupJson(READER_BACKUP_TYPES)
        ServerSettings.validateBackup(app)
        if (app.has("library_filters")) {
            val filters = app.getString("library_filters")
            if (LibraryFilters(options = LibraryFilters.parse(filters)).serialise() != filters) {
                throw IllegalArgumentException("Invalid library filters")
            }
        }
        if (app.has("dictionary_base_url")) {
            val baseUrl = app.getString("dictionary_base_url")
            if (DictionaryUrl.normalise(baseUrl) != baseUrl) {
                throw IllegalArgumentException("Invalid dictionary base URL")
            }
        }
        validateIds(app, mapOf(
            "theme_mode" to setOf("system", "light", "dark"),
            "volume_keys_mode" to VolumeKeyMode.entries.map { it.id }.toSet(),
            "tap_zones" to TapZones.entries.map { it.id }.toSet(),
            "edge_brightness" to com.chmouel.liseur.data.settings.EdgeBrightness.entries.map { it.id }.toSet(),
            "reader_orientation" to com.chmouel.liseur.data.settings.ReaderOrientation.entries.map { it.id }.toSet(),
            "library_sort" to setOf("recent", "title", "author", "added", "series"),
            "eink_mode" to setOf("auto", "on", "off"),
            "definition_target" to setOf("built_in", "external_app"),
            "upload_policy" to setOf("ask", "always", "never"),
            "stats_range" to com.chmouel.liseur.domain.StatsRange.entries.map { it.id }.toSet(),
            "highlight_tint_default" to HighlightTint.entries.map { it.name }.toSet(),
        ))
        if (app.has("tap_side_width") && app.getInt("tap_side_width") !in TapSideWidth.STOPS) {
            throw IllegalArgumentException("Unsupported setting value: tap_side_width")
        }
        if (app.has("highlight_tints_offered")) {
            val allowedTints = HighlightTint.entries.map { it.name }.toSet()
            val offered = app.getJSONArray("highlight_tints_offered")
            for (index in 0 until offered.length()) {
                if (offered.getString(index) !in allowedTints) {
                    throw IllegalArgumentException("Invalid offered highlight tint")
                }
            }
        }
        validateIds(reader, mapOf(
            "font" to com.chmouel.liseur.data.settings.ReaderFont.entries.map { it.id }.toSet(),
            "theme" to com.chmouel.liseur.data.settings.ReaderThemeChoice.entries.map { it.id }.toSet(),
            "page_turn_style" to com.chmouel.liseur.data.settings.PageTurnStyle.entries.map { it.id }.toSet(),
            "footer_mode" to com.chmouel.liseur.data.settings.FooterMode.entries.map { it.id }.toSet(),
            "footer_left" to com.chmouel.liseur.data.settings.FooterField.entries.map { it.id }.toSet(),
            "footer_right" to com.chmouel.liseur.data.settings.FooterField.entries.map { it.id }.toSet(),
            "column_mode" to com.chmouel.liseur.data.settings.ColumnMode.entries.map { it.id }.toSet(),
            "text_align" to com.chmouel.liseur.data.settings.ReaderTextAlign.entries.map { it.id }.toSet(),
            "font_weight" to com.chmouel.liseur.data.settings.ReaderFontWeight.entries.map { it.id }.toSet(),
        ))
        validateReaderNumbers(reader)
    }

    private fun canonicalizeBackupSettings(settings: JSONObject) {
        val sections = listOf("app", "reader")
        sections.forEach { section ->
            val values = settings.getJSONObject(section)
            values.keys().asSequence().toList().forEach { key ->
                val app = if (section == "app") JSONObject().put(key, values.get(key)) else JSONObject()
                val reader = if (section == "reader") JSONObject().put(key, values.get(key)) else JSONObject()
                try {
                    validateSettings(JSONObject().put("app", app).put("reader", reader))
                } catch (e: IllegalArgumentException) {
                    values.remove(key)
                    Log.w(TAG, "Skipping invalid stored backup setting: $section.$key")
                }
            }
        }
        validateSettings(settings)
    }

    private fun countKnownSettings(settings: JSONObject): Int {
        fun JSONObject.countKeys(known: Map<String, BackupValueType>): Int {
            val keys = keys()
            var count = 0
            while (keys.hasNext()) {
                if (keys.next() in known) count++
            }
            return count
        }

        return settings.getJSONObject("app").countKeys(APP_BACKUP_TYPES) +
            settings.getJSONObject("reader").countKeys(READER_BACKUP_TYPES)
    }

    private fun validateReaderNumbers(reader: JSONObject) {
        validateRange(reader, "font_size", TypographyRange.FONT_SIZE)
        validateRange(reader, "line_height", TypographyRange.LINE_HEIGHT)
        validateRange(reader, "page_margins", TypographyRange.PAGE_MARGINS)
        validateRange(reader, "letter_spacing", TypographyRange.LETTER_SPACING)
        validateRange(reader, "word_spacing", TypographyRange.WORD_SPACING)
        validateRange(reader, "paragraph_spacing", TypographyRange.PARAGRAPH_SPACING)

        if (reader.has("brightness")) {
            val brightness = reader.getDouble("brightness")
            if (brightness !in 0.0..1.0) throw IllegalArgumentException("Invalid brightness")
        }
        if (reader.has("auto_scroll_speed")) {
            val speed = reader.getDouble("auto_scroll_speed")
            if (
                speed !in AutoScrollPreference.MIN_STEP.toDouble()..AutoScrollPreference.MAX_STEP.toDouble() ||
                abs(speed - speed.roundToInt()) > NUMBER_EPSILON
            ) {
                throw IllegalArgumentException("Invalid auto-scroll speed")
            }
        }
    }

    private fun validateRange(json: JSONObject, key: String, range: TypographyRange) {
        if (!json.has(key)) return
        val value = json.getDouble(key)
        val sanitized = range.sanitize(value)
        if (sanitized == null || abs(sanitized - value) > NUMBER_EPSILON) {
            throw IllegalArgumentException("Invalid numeric setting: $key")
        }
    }

    private fun validateIds(json: JSONObject, allowed: Map<String, Set<String>>) {
        allowed.forEach { (key, values) ->
            val value = json.optString(key)
            val validImportedFont = key == "font" && UserFont.digestOf(value) != null
            if (json.has(key) && value !in values && !validImportedFont) {
                throw IllegalArgumentException("Unsupported setting value: $key")
            }
        }
    }

    private suspend fun hashFile(path: String, file: File): EntryMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > entryLimit(path)) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
                digest.update(buffer, 0, count)
            }
        }
        return EntryMetadata(path, size, digest.hexDigest())
    }

    private suspend fun writeFileEntry(
        zip: ZipOutputStream,
        path: String,
        file: File,
    ): EntryMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > entryLimit(path)) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
        }
        zip.closeEntry()
        return EntryMetadata(path, size, digest.hexDigest())
    }

    private fun removeStaging(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            Log.w(TAG, "Could not remove temporary backup data")
        }
    }

    private fun stagingDirectory(archiveId: String): File =
        File(context.cacheDir, "$PROCESS_STAGING_PREFIX$archiveId")

    /** Deletes cache files a killed process left behind, never one a live picker still waits on. */
    private fun sweepStaleExports() {
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_AGE_MS
        context.cacheDir.listFiles()
            ?.filter { it.isFile && EXPORT_TEMP_FILE.matches(it.name) && it.lastModified() < cutoff }
            ?.forEach { file ->
                if (!file.delete()) Log.w(TAG, "Could not remove a stale export staging file")
            }
    }

    private fun sweepStaleStaging() {
        val currentPrefix = PROCESS_STAGING_PREFIX
        context.cacheDir.listFiles()
            ?.filter {
                it.isDirectory && it.name.startsWith(STAGING_PREFIX) &&
                    !it.name.startsWith(currentPrefix)
            }
            ?.forEach(::removeStaging)
    }

    private fun MessageDigest.hexDigest(): String =
        digest().joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun entryLimit(path: String): Long = when {
        path.startsWith("books/") -> bookLimits.maxBookBytes
        path == SESSIONS_PATH -> MAX_SESSIONS_BYTES
        path == BOOKS_PATH -> MAX_BOOK_INDEX_BYTES
        path == TRANSLATIONS_PATH -> TranslationBackupRepository.MAX_BYTES
        else -> MAX_ENTRY_BYTES
    }

    private data class EntryMetadata(val path: String, val size: Long, val sha256: String)
    private data class ExpectedEntry(val size: Long, val sha256: String, val displayName: String?)
    private data class StagedEntry(
        val file: File,
        val size: Long,
        val sha256: String,
        val displayName: String? = null,
    )

    private sealed interface ArchiveResult {
        data class Valid(
            val stagingDirectory: File,
            val entries: Map<String, StagedEntry>,
        ) : ArchiveResult

        data class Error(val failure: SettingsBackupFailure) : ArchiveResult
    }

    private class BackupFailureException(
        val failure: SettingsBackupFailure,
        cause: Throwable? = null,
    ) : Exception(cause)

    companion object {
        private const val TAG = "SettingsBackup"
        private const val STAGING_PREFIX = "settings-backup-"
        private val PROCESS_STAGING_ID = UUID.randomUUID().toString()
        private val PROCESS_STAGING_PREFIX = "$STAGING_PREFIX$PROCESS_STAGING_ID-"
        private val STAGING_DIRECTORY =
            Regex("${Regex.escape(PROCESS_STAGING_PREFIX)}[0-9a-f-]{36}")
        private const val INDEX_PATH = "inspection.json"
        private val ARCHIVE_ID = Regex("[0-9a-f-]{36}")
        private val ENTRY_FILE = Regex("entry-[0-9]+")
        internal const val FORMAT = 7
        private const val MANIFEST_PATH = "manifest.json"
        private const val SETTINGS_PATH = "settings.json"
        private const val ANNOTATIONS_PATH = "annotations.json"
        private const val POSITIONS_PATH = "positions.json"
        private const val CONNECTIONS_PATH = "connections.enc.json"
        private const val COMPANION_PATH = "companion.json"
        private const val TRANSLATIONS_PATH = "translations.json"
        private const val SESSIONS_PATH = "sessions.json"
        private const val BOOKS_PATH = "books.json"
        // JSON trees expand well beyond their file size during inspection and restore.
        private const val MAX_READING_DATA_BYTES = 4L * 1024 * 1024
        private const val MAX_POSITIONS_BYTES = MAX_READING_DATA_BYTES
        private const val MAX_ANNOTATIONS_BYTES = MAX_READING_DATA_BYTES
        private const val MAX_COMPANION_BYTES = 16L * 1024 * 1024
        internal const val MAX_SESSIONS_BYTES = 16L * 1024 * 1024
        internal const val MAX_BOOK_INDEX_BYTES = 2L * 1024 * 1024
        internal const val MAX_BOOK_BYTES = 512L * 1024 * 1024
        internal const val MAX_BOOK_FILES = 2_000
        internal const val MAX_BOOKS_TOTAL_BYTES = 3L * 1024 * 1024 * 1024
        internal const val CACHE_HEADROOM_BYTES = 128L * 1024 * 1024
        private const val MAX_SETTINGS_BYTES = 1024L * 1024
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val MAX_FONT_FILES = UserFontRepository.MAX_FONTS
        private const val MAX_ENTRY_BYTES = UserFontRepository.MAX_BYTES
        internal const val MAX_ARCHIVE_ENTRIES = MAX_FONT_FILES + 9 + MAX_BOOK_FILES
        internal const val MAX_TOTAL_BYTES =
            MAX_FONT_FILES * MAX_ENTRY_BYTES +
                MAX_SETTINGS_BYTES +
                MAX_MANIFEST_BYTES +
                MAX_ANNOTATIONS_BYTES +
                MAX_POSITIONS_BYTES +
                MAX_COMPANION_BYTES +
                BackupEncryption.MAX_BYTES +
                TranslationBackupRepository.MAX_BYTES +
                MAX_SESSIONS_BYTES +
                MAX_BOOK_INDEX_BYTES
        private const val BUFFER_SIZE = 32 * 1024
        // A picker left open in another window or during doze can stay unanswered for
        // hours, so only a day-old staging file counts as one no live picker waits on.
        private const val STALE_EXPORT_AGE_MS = 24L * 60 * 60 * 1000
        private val EXPORT_TEMP_FILE =
            Regex("(settings|annotations|positions|companion|translations|sessions|books)-export-[0-9]+\\.[a-z]+")
        private val PREPARED_EXPORT_FILE = Regex("settings-export-[0-9]+\\.zip")
        private const val NUMBER_EPSILON = 0.000001
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val BOOK_ENTRY_PATH = Regex("books/[0-9a-f]{64}\\.epub")

        private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') &&
            '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
