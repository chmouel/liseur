package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.APP_BACKUP_TYPES
import com.chmouel.liseur.data.settings.AutoScrollPreference
import com.chmouel.liseur.data.settings.BackupValueType
import com.chmouel.liseur.data.settings.ImportResult as FontImportResult
import com.chmouel.liseur.data.settings.READER_BACKUP_TYPES
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.TypographyRange
import com.chmouel.liseur.data.settings.UserFontImport
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.applyBackupJson
import com.chmouel.liseur.data.settings.fonts.UserFont
import com.chmouel.liseur.data.settings.validateBackupJson
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.LibraryFilters
import com.chmouel.liseur.reader.annotations.HighlightTint
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
}

data class SettingsBackupPreview(val settingCount: Int, val fontCount: Int)

sealed interface SettingsBackupInspection {
    data class Ready(val preview: SettingsBackupPreview, val archiveId: String) : SettingsBackupInspection
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupInspection
}

sealed interface SettingsBackupExportResult {
    data class Exported(val fonts: Int) : SettingsBackupExportResult
    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupExportResult
}

sealed interface SettingsBackupRestoreResult {
    data class Restored(
        val fontsImported: Int,
        val fontsAlreadyPresent: Int,
        val fontFailures: Int,
    ) : SettingsBackupRestoreResult

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupRestoreResult
    data object PartiallyRestored : SettingsBackupRestoreResult
}

/** Versioned archive for deliberate, user initiated settings and font transfer. */
class SettingsBackupRepository(
    private val context: Context,
    private val appSettings: AppSettingsRepository,
    private val readerPreferences: ReaderPreferencesRepository,
    private val userFonts: UserFontRepository,
) {
    private val operationMutex = Mutex()

    suspend fun exportTo(target: Uri): SettingsBackupExportResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        try {
            val settings = JSONObject()
                .put("app", appSettings.backupValues())
                .put("reader", readerPreferences.backupValues())
            canonicalizeBackupSettings(settings)
            val settingsBytes = settings.toString().toByteArray(Charsets.UTF_8)
            if (settingsBytes.size > MAX_SETTINGS_BYTES) {
                return@withContext SettingsBackupExportResult.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val fonts = userFonts.backupFonts()
            if (fonts.size > MAX_FONT_FILES) {
                return@withContext SettingsBackupExportResult.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val listed = ArrayList<EntryMetadata>(fonts.size + 1)
            listed += EntryMetadata(SETTINGS_PATH, settingsBytes.size.toLong(), sha256(settingsBytes))
            var totalBytes = settingsBytes.size.toLong()
            val fontMetadata = ArrayList<EntryMetadata>(fonts.size)
            val fontNames = fonts.associate { "fonts/${it.fileName}" to it.displayName }
            for (font in fonts) {
                currentCoroutineContext().ensureActive()
                val path = "fonts/${font.fileName}"
                if (!safePath(path)) {
                    return@withContext SettingsBackupExportResult.Failed(
                        SettingsBackupFailure.INVALID_ARCHIVE,
                    )
                }
                val metadata = hashFile(path, font.file)
                totalBytes += metadata.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    return@withContext SettingsBackupExportResult.Failed(SettingsBackupFailure.TOO_LARGE)
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
                return@withContext SettingsBackupExportResult.Failed(SettingsBackupFailure.TOO_LARGE)
            }

            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: return@withContext SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, MANIFEST_PATH, manifestBytes)
                writeEntry(zip, SETTINGS_PATH, settingsBytes)
                fonts.zip(fontMetadata).forEach { (font, expected) ->
                    currentCoroutineContext().ensureActive()
                    val actual = writeFileEntry(zip, expected.path, font.file)
                    if (actual != expected) {
                        throw BackupFailureException(SettingsBackupFailure.FILE_ACCESS)
                    }
                }
            }
            SettingsBackupExportResult.Exported(fonts.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFailureException) {
            e.cause?.let { Log.w(TAG, "Settings export failed", it) }
            SettingsBackupExportResult.Failed(e.failure)
        } catch (e: Exception) {
            Log.w(TAG, "Settings export failed", e)
            SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS)
        }
        }
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

    suspend fun restore(archiveId: String): SettingsBackupRestoreResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        val archive = readStagedArchive(archiveId)
            ?: return@withContext SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE)
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

            var appSettingsRestored = false
            try {
                appSettings.restoreBackupValues(settings.getJSONObject("app"))
                appSettingsRestored = true
                readerPreferences.restoreBackupValues(settings.getJSONObject("reader"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Settings restore failed", e)
                return@withContext if (appSettingsRestored) {
                    SettingsBackupRestoreResult.PartiallyRestored
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
                return@withContext SettingsBackupRestoreResult.PartiallyRestored
            }
            SettingsBackupRestoreResult.Restored(imported, present, failures)
        } finally {
            removeStaging(archive.stagingDirectory)
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
        if (SETTINGS_PATH !in entries || entries.keys.any { it != SETTINGS_PATH && !it.startsWith("fonts/") }) {
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

            val entries = linkedMapOf<String, StagedEntry>()
            var totalBytes = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (
                        entry.isDirectory ||
                        !safePath(path) ||
                        path !in setOf(MANIFEST_PATH, SETTINGS_PATH) && !path.startsWith("fonts/") ||
                        path in entries ||
                        entries.size >= MAX_ARCHIVE_ENTRIES
                    ) {
                        return ArchiveResult.Error(SettingsBackupFailure.INVALID_ARCHIVE)
                    }
                    val sizeLimit = when (path) {
                        MANIFEST_PATH -> MAX_MANIFEST_BYTES
                        SETTINGS_PATH -> MAX_SETTINGS_BYTES
                        else -> MAX_ENTRY_BYTES
                    }
                    val staged = stageEntry(
                        zip = zip,
                        file = File(staging, "entry-${entries.size}"),
                        sizeLimit = minOf(sizeLimit, MAX_TOTAL_BYTES - totalBytes),
                    )
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
            if (manifest.opt("format") != FORMAT) {
                return ArchiveResult.Error(SettingsBackupFailure.UNSUPPORTED_VERSION)
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
                        rawSize in 0.0..MAX_ENTRY_BYTES.toDouble()
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
                    size !in 0..MAX_ENTRY_BYTES ||
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
        zip: ZipInputStream,
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
                        zip.read(buffer)
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
            "tap_zones" to setOf("standard", "swapped"),
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
                if (size > MAX_ENTRY_BYTES) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
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
                if (size > MAX_ENTRY_BYTES) throw BackupFailureException(SettingsBackupFailure.TOO_LARGE)
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
        private const val FORMAT = 1
        private const val MANIFEST_PATH = "manifest.json"
        private const val SETTINGS_PATH = "settings.json"
        private const val MAX_SETTINGS_BYTES = 1024L * 1024
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val MAX_FONT_FILES = UserFontRepository.MAX_FONTS
        private const val MAX_ENTRY_BYTES = UserFontRepository.MAX_BYTES
        private const val MAX_ARCHIVE_ENTRIES = MAX_FONT_FILES + 2
        private const val MAX_TOTAL_BYTES =
            MAX_FONT_FILES * MAX_ENTRY_BYTES + MAX_SETTINGS_BYTES + MAX_MANIFEST_BYTES
        private const val BUFFER_SIZE = 32 * 1024
        private const val NUMBER_EPSILON = 0.000001
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') &&
            '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
