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
import com.chmouel.liseur.data.settings.TypographyRange
import com.chmouel.liseur.data.settings.UserFontImport
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.VolumeKeyMode
import com.chmouel.liseur.data.settings.applyBackupJson
import com.chmouel.liseur.data.settings.fonts.UserFont
import com.chmouel.liseur.data.settings.validateBackupJson
import com.chmouel.liseur.domain.BackedUpReadingPosition
import com.chmouel.liseur.domain.CompanionBackupContents
import com.chmouel.liseur.domain.decodeCompanionBackup
import com.chmouel.liseur.domain.decodeTranslationBackup
import com.chmouel.liseur.domain.BackedUpTranslation
import com.chmouel.liseur.domain.decodeReadingPositionBackup
import com.chmouel.liseur.domain.BackupContents
import com.chmouel.liseur.domain.decodeAnnotationBackup
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.LibraryFilters
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.data.remote.ServerKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
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
    ) : SettingsBackupExportResult
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupExportResult
}

/** A complete archive waiting in app-private cache for a destination chosen by the user. */
data class PreparedExport(
    val file: File,
    val fonts: Int,
    val annotations: Int = 0,
    val liseurSyncNeedsPairing: Boolean = false,
    val translationBooks: Int = 0,
    val translationBooksSkipped: Int = 0,
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
    ) : SettingsBackupRestoreResult

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupRestoreResult
    data class PartiallyRestored(val liseurSyncNeedsPairing: Boolean = false) : SettingsBackupRestoreResult
}

/** Versioned archive for reading data, settings, fonts, and optional encrypted connections. */
class SettingsBackupRepository(
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
) {
    private val operationMutex = Mutex()

    suspend fun exportTo(target: Uri, password: CharArray? = null, includeTranslations: Boolean = true): SettingsBackupExportResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            when (val preparation = prepareExportLocked(password, includeTranslations)) {
                is SettingsBackupPreparation.Failed ->
                    SettingsBackupExportResult.Failed(preparation.failure)
                is SettingsBackupPreparation.Prepared -> try {
                    writePreparedExportLocked(preparation.export, target)
                } finally {
                    preparation.export.file.delete()
                }
            }
        }
    }

    /** Builds the whole ZIP in cache so the document picker never creates an empty destination. */
    suspend fun prepareExport(
        password: CharArray? = null,
        includeTranslations: Boolean = true,
    ): SettingsBackupPreparation {
        var prepared: PreparedExport? = null
        try {
            return withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    prepareExportLocked(password, includeTranslations).also {
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
    suspend fun writeExport(prepared: PreparedExport, target: Uri): SettingsBackupExportResult {
        var started = false
        try {
            return withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    started = true
                    try {
                        writePreparedExportLocked(prepared, target)
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
    ): SettingsBackupPreparation {
        sweepStaleExports()
        var completed = false
        var archiveFile: File? = null
        var annotationFile: File? = null
        var positionFile: File? = null
        var companionFile: File? = null
        var translationFile: File? = null
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

            val fonts = userFonts.backupFonts()
            if (fonts.size > MAX_FONT_FILES) {
                return SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val listed = ArrayList<EntryMetadata>(fonts.size + 4)
            listed += EntryMetadata(SETTINGS_PATH, settingsBytes.size.toLong(), sha256(settingsBytes))
            listed += annotationMetadata
            listed += positionMetadata
            listed += companionMetadata
            translationMetadata?.let { listed += it }
            connectionBytes?.let { listed += EntryMetadata(CONNECTIONS_PATH, it.size.toLong(), sha256(it)) }
            var totalBytes = settingsBytes.size.toLong() + annotationMetadata.size + positionMetadata.size + companionMetadata.size + (connectionBytes?.size ?: 0) + (translationMetadata?.size ?: 0)
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
                PreparedExport(archive, fonts.size, annotationCount, liseurSyncNeedsPairing,
                    translationResult.books, translationResult.skipped),
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
            if (!completed) archiveFile?.delete()
        }
    }

    private suspend fun writePreparedExportLocked(
        prepared: PreparedExport,
        target: Uri,
    ): SettingsBackupExportResult {
        var completed = false
        try {
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: return SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
            output.use { sink ->
                prepared.file.inputStream().use { source ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            }
            completed = true
            return SettingsBackupExportResult.Exported(
                prepared.fonts, prepared.annotations, prepared.liseurSyncNeedsPairing,
                prepared.translationBooks, prepared.translationBooksSkipped,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Settings export failed", e)
            return SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
        } finally {
            if (!completed) discardExport(target)
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

    suspend fun inspect(source: Uri): SettingsBackupInspection = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        sweepStaleStaging()
        when (val result = readArchive(source)) {
            is ArchiveResult.Error -> SettingsBackupInspection.Failed(result.failure)
            is ArchiveResult.Valid -> {
                var retained = false
                try {
                    val settings = decodeSettings(result.entries.getValue(SETTINGS_PATH).file)
                    validateSettings(settings)
                    val annotationPreview = annotations.previewContents(decodeAnnotations(result.entries))
                    val positionContents = decodePositions(result.entries)
                    val companionContents = decodeCompanion(result.entries)
                    val translationContents = decodeTranslations(result.entries)
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

    suspend fun restore(archiveId: String, password: CharArray? = null): SettingsBackupRestoreResult = withContext(Dispatchers.IO) {
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
            val annotationResult = try {
                annotations.importContents(annotationContents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Annotation restore stopped after settings were restored", e)
                return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
            }
            val restoredPositions = try {
                positions.restore(positionContents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading position restore stopped after settings were restored", e)
                return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
            }
            val companionRecapsAdded = try {
                if (companionContents.isEmpty()) 0 else companion.restore(companionContents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Companion restore stopped after settings were restored", e)
                return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
            }
            val translationResult = try {
                if (translationContents.isEmpty()) TranslationBackupResult()
                else translations?.restore(translationContents) ?: TranslationBackupResult(0, translationContents.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Translation restore stopped after settings were restored", e)
                return@withContext SettingsBackupRestoreResult.PartiallyRestored(liseurSyncNeedsPairing)
            }
            SettingsBackupRestoreResult.Restored(
                imported, present, failures, annotationResult.added, annotationResult.alreadyHere, restoredPositions,
                companionRecapsAdded, liseurSyncNeedsPairing, translationResult.books, translationResult.skipped,
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
        if (SETTINGS_PATH !in entries || entries.keys.any { it !in setOf(SETTINGS_PATH, ANNOTATIONS_PATH, POSITIONS_PATH, COMPANION_PATH, CONNECTIONS_PATH, TRANSLATIONS_PATH) && !it.startsWith("fonts/") }) {
            return null
        }
        return ArchiveResult.Valid(directory, entries)
    }

    private suspend fun readArchive(source: Uri): ArchiveResult {
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
            var totalBytes = 0L
            var readingDataBytes = 0L
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (
                        entry.isDirectory ||
                        !safePath(path) ||
                        path !in setOf(MANIFEST_PATH, SETTINGS_PATH, ANNOTATIONS_PATH, POSITIONS_PATH, COMPANION_PATH, CONNECTIONS_PATH, TRANSLATIONS_PATH) && !path.startsWith("fonts/") ||
                        path in entries ||
                        entries.size >= MAX_ARCHIVE_ENTRIES
                    ) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }
                    val sizeLimit = when (path) {
                        MANIFEST_PATH -> MAX_MANIFEST_BYTES
                        SETTINGS_PATH -> MAX_SETTINGS_BYTES
                        ANNOTATIONS_PATH -> MAX_ANNOTATIONS_BYTES
                        POSITIONS_PATH -> MAX_POSITIONS_BYTES
                        COMPANION_PATH -> MAX_COMPANION_BYTES
                        TRANSLATIONS_PATH -> TranslationBackupRepository.MAX_BYTES
                        CONNECTIONS_PATH -> BackupEncryption.MAX_BYTES
                        else -> MAX_ENTRY_BYTES
                    }
                    val staged = stageEntry(
                        input = zip,
                        file = File(staging, "entry-${entries.size}"),
                        sizeLimit = minOf(sizeLimit, MAX_TOTAL_BYTES - totalBytes),
                    )
                    if (path == ANNOTATIONS_PATH || path == POSITIONS_PATH) {
                        readingDataBytes += staged.size
                        if (readingDataBytes > MAX_READING_DATA_BYTES) {
                            return ArchiveResult.Error(SettingsBackupFailure.TOO_LARGE)
                        }
                    }
                    totalBytes += staged.size
                    entries[path] = staged
                    zip.closeEntry()
                }
            }

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
            if (manifest.opt("format") !in setOf(1, 2, 3, 4, 5, FORMAT)) {
                return ArchiveResult.Error(SettingsBackupFailure.UNSUPPORTED_VERSION)
            }
            if ((manifest.optInt("format") >= 2) != (ANNOTATIONS_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if ((manifest.optInt("format") >= 3) != (POSITIONS_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if ((manifest.optInt("format") >= 4) != (COMPANION_PATH in entries)) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (manifest.optInt("format") < 5 && CONNECTIONS_PATH in entries) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            if (manifest.optInt("format") < 6 && TRANSLATIONS_PATH in entries) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            val listed = manifest.optJSONArray("entries")
                ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            val expected = mutableMapOf<String, ExpectedEntry>()
            for (index in 0 until listed.length()) {
                val item = listed.optJSONObject(index)
                    ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                val path = item.opt("path") as? String
                    ?: return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                val size = (item.opt("size") as? Number)?.toDouble()?.let { rawSize ->
                    if (
                        rawSize.isFinite() &&
                        rawSize % 1.0 == 0.0 &&
                        rawSize in 0.0..entryLimit(path).toDouble()
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
                    size !in 0..entryLimit(path) ||
                    !SHA256.matches(digest) ||
                    expected.put(path, ExpectedEntry(size, digest, displayName)) != null
                ) {
                    return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                }
            }
            if (expected.keys != entries.keys - MANIFEST_PATH) {
                return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
            }
            expected.forEach { (path, check) ->
                val entry = entries.getValue(path)
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
                }
            }
        } catch (e: BackupFailureException) {
            throw e
        } catch (e: IOException) {
            throw BackupFailureException(SettingsBackupFailure.STORAGE, e)
        }
        return StagedEntry(file, size, digest.hexDigest())
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
            "tap_zones" to setOf("standard", "swapped"),
            "edge_brightness" to com.chmouel.liseur.data.settings.EdgeBrightness.entries.map { it.id }.toSet(),
            "library_sort" to setOf("recent", "title", "author", "added", "series"),
            "eink_mode" to setOf("auto", "on", "off"),
            "definition_target" to setOf("built_in", "external_app"),
            "upload_policy" to setOf("ask", "always", "never"),
            "stats_range" to com.chmouel.liseur.domain.StatsRange.entries.map { it.id }.toSet(),
            "highlight_tint_default" to HighlightTint.entries.map { it.name }.toSet(),
        ))
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
        private const val FORMAT = 6
        private const val MANIFEST_PATH = "manifest.json"
        private const val SETTINGS_PATH = "settings.json"
        private const val ANNOTATIONS_PATH = "annotations.json"
        private const val POSITIONS_PATH = "positions.json"
        private const val CONNECTIONS_PATH = "connections.enc.json"
        private const val COMPANION_PATH = "companion.json"
        private const val TRANSLATIONS_PATH = "translations.json"
        // JSON trees expand well beyond their file size during inspection and restore.
        private const val MAX_READING_DATA_BYTES = 4L * 1024 * 1024
        private const val MAX_POSITIONS_BYTES = MAX_READING_DATA_BYTES
        private const val MAX_ANNOTATIONS_BYTES = MAX_READING_DATA_BYTES
        private const val MAX_COMPANION_BYTES = 16L * 1024 * 1024
        private const val MAX_SETTINGS_BYTES = 1024L * 1024
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val MAX_FONT_FILES = UserFontRepository.MAX_FONTS
        private const val MAX_ENTRY_BYTES = UserFontRepository.MAX_BYTES
        private const val MAX_ARCHIVE_ENTRIES = MAX_FONT_FILES + 7
        private const val MAX_TOTAL_BYTES =
            MAX_FONT_FILES * MAX_ENTRY_BYTES + MAX_SETTINGS_BYTES + MAX_MANIFEST_BYTES + MAX_ANNOTATIONS_BYTES + MAX_POSITIONS_BYTES + MAX_COMPANION_BYTES + BackupEncryption.MAX_BYTES + TranslationBackupRepository.MAX_BYTES
        private const val BUFFER_SIZE = 32 * 1024
        // A picker left open in another window or during doze can stay unanswered for
        // hours, so only a day-old staging file counts as one no live picker waits on.
        private const val STALE_EXPORT_AGE_MS = 24L * 60 * 60 * 1000
        private val EXPORT_TEMP_FILE =
            Regex("(settings|annotations|positions|companion|translations)-export-[0-9]+\\.[a-z]+")
        private val PREPARED_EXPORT_FILE = Regex("settings-export-[0-9]+\\.zip")
        private const val NUMBER_EPSILON = 0.000001
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun entryLimit(path: String) =
            if (path == TRANSLATIONS_PATH) TranslationBackupRepository.MAX_BYTES else MAX_ENTRY_BYTES

        private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') &&
            '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
