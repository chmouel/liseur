package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ImportResult as FontImportResult
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.fonts.UserFont
import com.chmouel.liseur.data.settings.applyBackupJson
import com.chmouel.liseur.data.settings.validateBackupJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SettingsBackupPreview(val settingCount: Int, val fontCount: Int)

sealed interface SettingsBackupInspection {
    data class Ready(val preview: SettingsBackupPreview) : SettingsBackupInspection
    data class Failed(val reason: String) : SettingsBackupInspection
}

sealed interface SettingsBackupResult {
    data class Exported(val fonts: Int) : SettingsBackupResult
    data class Restored(val fontsImported: Int, val fontsAlreadyPresent: Int, val fontFailures: Int) : SettingsBackupResult
    data class Failed(val reason: String) : SettingsBackupResult
}

/** Versioned archive for deliberate, user initiated settings and font transfer. */
class SettingsBackupRepository(
    private val context: Context,
    private val appSettings: AppSettingsRepository,
    private val readerPreferences: ReaderPreferencesRepository,
    private val userFonts: UserFontRepository,
) {
    suspend fun exportTo(target: Uri): SettingsBackupResult = withContext(Dispatchers.IO) {
        val app = appSettings.backupValues()
        val reader = readerPreferences.backupValues()
        val fonts = userFonts.backupFonts()
        val entries = linkedMapOf<String, ByteArray>()
        entries[SETTINGS_PATH] = JSONObject()
            .put("app", app)
            .put("reader", reader)
            .toString()
            .toByteArray(Charsets.UTF_8)
        fonts.forEach { font ->
            currentCoroutineContext().ensureActive()
            entries["fonts/${font.fileName}"] = font.file.readBytes()
        }
        val manifest = JSONObject().put("format", FORMAT).put("application", "liseur")
        val listed = JSONArray()
        entries.forEach { (path, bytes) ->
            listed.put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", sha256(bytes)))
        }
        manifest.put("entries", listed)
        try {
            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                ZipOutputStream(output).use { zip ->
                    writeEntry(zip, MANIFEST_PATH, manifest.toString().toByteArray())
                    entries.forEach { (path, bytes) ->
                        currentCoroutineContext().ensureActive()
                        writeEntry(zip, path, bytes)
                    }
                }
            } ?: return@withContext SettingsBackupResult.Failed("Could not open the destination")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SettingsBackupResult.Failed(e.message ?: "Could not write the backup")
        }
        SettingsBackupResult.Exported(fonts.size)
    }

    suspend fun inspect(source: Uri): SettingsBackupInspection = withContext(Dispatchers.IO) {
        when (val archive = readArchive(source)) {
            is ArchiveResult.Error -> SettingsBackupInspection.Failed(archive.reason)
            is ArchiveResult.Valid -> {
                val settings = runCatching { JSONObject(archive.entries.getValue(SETTINGS_PATH).toString(Charsets.UTF_8)) }
                    .getOrElse { return@withContext SettingsBackupInspection.Failed("Settings data is malformed") }
                try {
                    validateSettings(settings)
                    archive.entries.keys.filter { it.startsWith("fonts/") }.forEach { path ->
                        val name = path.removePrefix("fonts/")
                        val digest = name.substringBefore('.')
                        val extension = name.substringAfter('.', "")
                        if (UserFont.fileNameFor(digest, extension) != name) {
                            return@withContext SettingsBackupInspection.Failed("A font entry has an invalid name")
                        }
                        if (sha256(archive.entries.getValue(path)) != digest) {
                            return@withContext SettingsBackupInspection.Failed("A font identifier does not match its contents")
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    return@withContext SettingsBackupInspection.Failed(e.message ?: "Settings data is malformed")
                }
                SettingsBackupInspection.Ready(
                    SettingsBackupPreview(
                        settingCount = settings.optJSONObject("app")?.length().orZero() +
                            settings.optJSONObject("reader")?.length().orZero(),
                        fontCount = archive.entries.keys.count { it.startsWith("fonts/") },
                    ),
                )
            }
        }
    }

    suspend fun restore(source: Uri): SettingsBackupResult = withContext(Dispatchers.IO) {
        val archive = when (val result = readArchive(source)) {
            is ArchiveResult.Error -> return@withContext SettingsBackupResult.Failed(result.reason)
            is ArchiveResult.Valid -> result
        }
        val settings = try {
            JSONObject(archive.entries.getValue(SETTINGS_PATH).toString(Charsets.UTF_8)).also(::validateSettings)
        } catch (e: Exception) {
            return@withContext SettingsBackupResult.Failed(e.message ?: "Settings data is malformed")
        }
        // Validation is complete before any store is edited. The two stores are
        // independent, so storage errors after this point are reported as partial.
        try {
            appSettings.restoreBackupValues(settings.getJSONObject("app"))
            readerPreferences.restoreBackupValues(settings.getJSONObject("reader"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SettingsBackupResult.Failed(e.message ?: "Could not restore settings")
        }
        var imported = 0
        var present = 0
        var failures = 0
        val staging = File(context.cacheDir, "backup-fonts-${System.nanoTime()}")
        try {
            staging.mkdirs()
            archive.entries.filterKeys { it.startsWith("fonts/") }.forEach { (path, bytes) ->
                currentCoroutineContext().ensureActive()
                val name = path.removePrefix("fonts/")
                val digest = name.substringBefore('.')
                val extension = name.substringAfter('.', "")
                if (UserFont.fileNameFor(digest, extension) != name) {
                    failures++
                } else {
                    val file = File(staging, name)
                    file.writeBytes(bytes)
                    when (userFonts.import(Uri.fromFile(file), name)) {
                        is FontImportResult.Imported -> imported++
                        is FontImportResult.AlreadyPresent -> present++
                        else -> failures++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SettingsBackupResult.Failed(
                "Settings were restored, but font restore stopped: ${e.message ?: "storage error"}",
            )
        } finally {
            staging.deleteRecursively()
        }
        SettingsBackupResult.Restored(imported, present, failures)
    }

    private suspend fun readArchive(source: Uri): ArchiveResult {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: return ArchiveResult.Error("Could not open the backup")
            ZipInputStream(input).use { zip ->
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !safePath(entry.name) || entry.name in entries) {
                        return ArchiveResult.Error("Backup contains an unsafe or duplicate entry")
                    }
                    val bytes = zip.readBytesBounded(MAX_ENTRY_BYTES)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) return ArchiveResult.Error("Backup is too large")
                    entries[entry.name] = bytes
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return ArchiveResult.Error(e.message ?: "Backup is damaged")
        }
        val manifest = try {
            JSONObject(entries[MANIFEST_PATH]?.toString(Charsets.UTF_8) ?: return ArchiveResult.Error("Backup manifest is missing"))
        } catch (_: Exception) {
            return ArchiveResult.Error("Backup manifest is malformed")
        }
        if (manifest.optInt("format", -1) != FORMAT) return ArchiveResult.Error("Unsupported backup version")
        val listed = manifest.optJSONArray("entries") ?: return ArchiveResult.Error("Backup manifest is incomplete")
        val expected = mutableMapOf<String, Pair<Int, String>>()
        for (i in 0 until listed.length()) {
            val item = listed.optJSONObject(i) ?: return ArchiveResult.Error("Backup manifest is malformed")
            val path = item.optString("path")
            val size = item.optLong("size", -1).takeIf { it in 0..MAX_ENTRY_BYTES }?.toInt()
                ?: return ArchiveResult.Error("Backup entry size is invalid")
            val digest = item.optString("sha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: return ArchiveResult.Error("Backup checksum is invalid")
            if (!safePath(path) || path == MANIFEST_PATH || expected.put(path, size to digest) != null) {
                return ArchiveResult.Error("Backup manifest contains an unsafe or duplicate entry")
            }
        }
        if (expected.keys != entries.keys - MANIFEST_PATH) return ArchiveResult.Error("Backup entries do not match the manifest")
        expected.forEach { (path, check) ->
            val bytes = entries.getValue(path)
            if (bytes.size != check.first || sha256(bytes) != check.second) return ArchiveResult.Error("Backup checksum failed")
        }
        val settings = entries[SETTINGS_PATH] ?: return ArchiveResult.Error("Settings data is missing")
        if (settings.size > MAX_SETTINGS_BYTES) return ArchiveResult.Error("Settings data is too large")
        return ArchiveResult.Valid(entries - MANIFEST_PATH)
    }

    private fun validateSettings(settings: JSONObject) {
        val app = settings.optJSONObject("app") ?: throw IllegalArgumentException("App settings are missing")
        val reader = settings.optJSONObject("reader") ?: throw IllegalArgumentException("Reader settings are missing")
        // Running the existing strict type decoder on throwaway preferences validates
        // known fields before either DataStore receives a write.
        app.validateBackupJson(APP_BACKUP_TYPES)
        reader.validateBackupJson(READER_BACKUP_TYPES)
        validateIds(app, mapOf(
            "theme_mode" to setOf("system", "light", "dark"),
            "tap_zones" to setOf("standard", "swapped"),
            "library_sort" to setOf("recent", "title", "author", "added", "series"),
            "eink_mode" to setOf("auto", "on", "off"),
            "definition_target" to setOf("built_in", "external_app"),
            "upload_policy" to setOf("ask", "always", "never"),
            "stats_range" to com.chmouel.liseur.domain.StatsRange.entries.map { it.id }.toSet(),
        ))
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
    }

    private fun validateIds(json: JSONObject, allowed: Map<String, Set<String>>) {
        allowed.forEach { (key, values) ->
            val value = json.optString(key)
            val validImportedFont = key == "font" && UserFont.digestOf(value) != null
            if (json.has(key) && value !in values && !validImportedFont) {
                throw IllegalArgumentException("Unsupported value for setting: $key")
            }
        }
    }


    private sealed interface ArchiveResult {
        data class Valid(val entries: Map<String, ByteArray>) : ArchiveResult
        data class Error(val reason: String) : ArchiveResult
    }

    companion object {
        private const val FORMAT = 1
        private const val MANIFEST_PATH = "manifest.json"
        private const val SETTINGS_PATH = "settings.json"
        private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_SETTINGS_BYTES = 1024 * 1024
        private const val MAX_TOTAL_BYTES = 600L * 1024 * 1024

        private val APP_BACKUP_TYPES = mapOf(
            "theme_mode" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "dynamic_color" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "volume_keys_turn_pages" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "tap_zones" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "pinch_to_resize" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "resume_last_book" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "keep_screen_on" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "scroll_mode" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "library_sort" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "library_sort_reversed" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "library_filters" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "library_group_by_series" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "eink_mode" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "color_eink" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "vendor_refresh" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "definition_target" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "dictionary_lookup_enabled" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "dictionary_base_url" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "upload_policy" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "stats_range" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "highlight_tints_offered" to com.chmouel.liseur.data.settings.BackupValueType.STRING_SET,
            "highlight_tint_default" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
        )
        private val READER_BACKUP_TYPES = mapOf(
            "font" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "font_size" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
            "theme" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "line_height" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
            "page_margins" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
            "brightness" to com.chmouel.liseur.data.settings.BackupValueType.FLOAT,
            "page_turn_style" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "page_turn_animation" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "footer_mode" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "footer_left" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "footer_right" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "column_mode" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "auto_scroll_speed" to com.chmouel.liseur.data.settings.BackupValueType.FLOAT,
            "text_align" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "font_weight" to com.chmouel.liseur.data.settings.BackupValueType.STRING,
            "hyphens" to com.chmouel.liseur.data.settings.BackupValueType.BOOLEAN,
            "letter_spacing" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
            "word_spacing" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
            "paragraph_spacing" to com.chmouel.liseur.data.settings.BackupValueType.DOUBLE,
        )

        private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') &&
            '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }

        private fun java.util.zip.ZipInputStream.readBytesBounded(limit: Long): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("Backup entry is too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun Int?.orZero() = this ?: 0
    }
}
