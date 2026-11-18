package com.chmouel.liseur.reader.dictionary

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device StarDict dictionaries, downloaded from reader.dict or imported
 * from a local archive.
 *
 * Multiple dictionaries can sit on the phone at once — one per language the
 * reader reads in — and [matchFor] picks the one that fits the book's own
 * language before falling back to an active general dictionary. Operations run
 * on an application-scoped supervisor so leaving Settings mid-download does
 * not abort a 40 MB archive unpack.
 */
internal object OfflineDictionary {
    data class Pack(
        val id: String,
        val name: String,
        val language: String,
        val enabled: Boolean,
        val sizeBytes: Long,
        val wordCount: Int,
        val isReaderDict: Boolean,
    )

    sealed interface Operation {
        data object Idle : Operation
        data class Downloading(
            val language: String,
            val downloadedBytes: Long,
            val totalBytes: Long?,
        ) : Operation
        data class Unpacking(val label: String) : Operation
        data class Failed(val reason: FailureReason, val language: String? = null) : Operation
    }

    enum class FailureReason {
        DOWNLOAD,
        IMPORT,
        REMOVE,
    }

    data class State(
        val packs: List<Pack> = emptyList(),
        val operation: Operation = Operation.Idle,
    )

    data class CatalogEntry(
        val language: String,
        val approxBytes: Long,
    )

    /**
     * Monolingual StarDict editions published by reader.dict, with their
     * approximate compressed archive sizes so the picker can set expectations
     * before a reader taps a 60 MB download on mobile data.
     */
    val catalog: List<CatalogEntry> = listOf(
        CatalogEntry("bg", 2_200_000L),
        CatalogEntry("ca", 4_400_000L),
        CatalogEntry("cs", 4_100_000L),
        CatalogEntry("da", 680_000L),
        CatalogEntry("de", 18_400_000L),
        CatalogEntry("el", 13_100_000L),
        CatalogEntry("en", 62_300_000L),
        CatalogEntry("eo", 2_100_000L),
        CatalogEntry("es", 10_800_000L),
        CatalogEntry("eu", 500_000L),
        CatalogEntry("fi", 9_500_000L),
        CatalogEntry("fr", 40_600_000L),
        CatalogEntry("it", 7_600_000L),
        CatalogEntry("ja", 8_200_000L),
        CatalogEntry("jbo", 35_000L),
        CatalogEntry("ko", 2_400_000L),
        CatalogEntry("la", 370_000L),
        CatalogEntry("lt", 2_500_000L),
        CatalogEntry("mg", 8_700_000L),
        CatalogEntry("nl", 17_700_000L),
        CatalogEntry("no", 1_100_000L),
        CatalogEntry("pl", 9_800_000L),
        CatalogEntry("pt", 7_000_000L),
        CatalogEntry("ro", 6_900_000L),
        CatalogEntry("ru", 45_600_000L),
        CatalogEntry("sv", 6_300_000L),
        CatalogEntry("th", 2_100_000L),
        CatalogEntry("tr", 7_800_000L),
        CatalogEntry("uk", 2_700_000L),
        CatalogEntry("zh", 4_100_000L),
    )

    val downloadLanguages: List<String> = catalog.map(CatalogEntry::language)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { OkHttpClient.Builder().build() }
    private val lock = Any()
    private val _state = MutableStateFlow(State())
    @Volatile private var loaded = false
    private var activeJob: Job? = null
    private val cached = LinkedHashMap<String, StarDict>(2, 0.75f, true)
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var cancelRequested = false

    fun state(context: Context): StateFlow<State> {
        ensureLoaded(context.applicationContext)
        return _state.asStateFlow()
    }

    fun packs(context: Context): List<Pack> {
        ensureLoaded(context.applicationContext)
        return _state.value.packs
    }

    /**
     * Picks the best enabled offline dictionary for [languages] (the book's
     * declared language chain):
     * 1. An enabled pack whose primary language matches one of [languages] in order.
     * 2. An enabled pack with no declared language (custom StarDict ZIP).
     * 3. When only a single enabled pack is installed and the book declared no
     *    specific language (only the default fallback), use that pack.
     */
    fun matchFor(context: Context, languages: List<String>, bookLanguage: String?): Pack? {
        val enabled = packs(context).filter(Pack::enabled)
        if (enabled.isEmpty()) return null
        val requested = buildList {
            if (!bookLanguage.isNullOrBlank()) add(primaryLanguage(bookLanguage))
            languages.mapTo(this, ::primaryLanguage)
        }.filter(String::isNotBlank).distinct()

        if (!bookLanguage.isNullOrBlank()) {
            val primary = primaryLanguage(bookLanguage)
            enabled.firstOrNull { samePrimary(it.language, primary) }?.let { return it }
            // Languageless imported dictionary applies to any book.
            enabled.firstOrNull { it.language.isBlank() }?.let { return it }
            return null
        }

        for (lang in requested) {
            enabled.firstOrNull { samePrimary(it.language, lang) }?.let { return it }
        }
        enabled.firstOrNull { it.language.isBlank() }?.let { return it }
        return enabled.singleOrNull()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val app = context.applicationContext
        ensureLoaded(app)
        synchronized(lock) {
            val updated = _state.value.packs.map { pack ->
                if (pack.id == id) pack.copy(enabled = enabled) else pack
            }
            if (savePacks(app, updated)) {
                _state.value = _state.value.copy(packs = updated)
            }
        }
    }

    fun clearFailure() {
        val current = _state.value
        if (current.operation is Operation.Failed) {
            _state.value = current.copy(operation = Operation.Idle)
        }
    }

    fun cancel() {
        cancelRequested = true
        activeJob?.cancel()
        activeCall?.cancel()
    }

    fun startDownload(context: Context, language: String) {
        require(language in downloadLanguages)
        val app = context.applicationContext
        ensureLoaded(app)
        if (isBusy()) return
        val expected = catalog.firstOrNull { it.language == language }?.approxBytes
        _state.value = _state.value.copy(
            operation = Operation.Downloading(language = language, downloadedBytes = 0L, totalBytes = expected),
        )
        cancelRequested = false
        activeJob = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                installPack(
                    context = app,
                    explicitLanguage = language,
                    replaceLanguage = language,
                    open = {
                        val request = Request.Builder()
                            .url("https://www.reader-dict.com/file/$language/dict-$language-$language.zip")
                            .build()
                        val call = client.newCall(request)
                        activeCall = call
                        // A cancel that came before the call was published had nothing to interrupt.
                        if (cancelRequested) call.cancel()
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            response.close()
                            throw IOException("HTTP ${response.code}")
                        }
                        val body = response.body
                        val contentLength = body.contentLength().takeIf { it > 0L } ?: expected
                        ProgressInputStream(body.byteStream()) { readBytes ->
                            _state.value = _state.value.copy(
                                operation = Operation.Downloading(
                                    language = language,
                                    downloadedBytes = readBytes,
                                    totalBytes = contentLength,
                                ),
                            )
                        }
                    },
                    onUnpacking = {
                        _state.value = _state.value.copy(operation = Operation.Unpacking(language))
                    },
                )
                _state.value = _state.value.copy(operation = Operation.Idle)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(operation = Operation.Idle)
                throw cancelled
            } catch (_: Exception) {
                // Cancelling the call surfaces as an IOException; that is not a failed download.
                _state.value = _state.value.copy(
                    operation = if (cancelRequested) {
                        Operation.Idle
                    } else {
                        Operation.Failed(FailureReason.DOWNLOAD, language)
                    },
                )
            } finally {
                activeJob = null
                activeCall = null
            }
        }
    }

    fun startImport(context: Context, uri: Uri, displayName: String? = null) {
        val app = context.applicationContext
        ensureLoaded(app)
        if (isBusy()) return
        val label = displayName?.removeSuffix(".zip").orEmpty()
        _state.value = _state.value.copy(operation = Operation.Unpacking(label))
        cancelRequested = false
        activeJob = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                installPack(
                    context = app,
                    explicitLanguage = null,
                    replaceLanguage = null,
                    open = {
                        app.contentResolver.openInputStream(uri)
                            ?: throw IOException("Could not open dictionary archive")
                    },
                    onUnpacking = {},
                )
                _state.value = _state.value.copy(operation = Operation.Idle)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(operation = Operation.Idle)
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    operation = if (cancelRequested) Operation.Idle else Operation.Failed(FailureReason.IMPORT),
                )
            } finally {
                activeJob = null
            }
        }
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        ensureLoaded(app)
        if (isBusy()) return
        scope.launch {
            val targetDir: File?
            synchronized(lock) {
                val existing = _state.value.packs
                if (existing.none { it.id == id }) return@launch
                val updated = existing.filterNot { it.id == id }
                if (!savePacks(app, updated)) {
                    _state.value = _state.value.copy(operation = Operation.Failed(FailureReason.REMOVE))
                    return@launch
                }
                cached.remove(id)
                _state.value = _state.value.copy(packs = updated)
                targetDir = directory(app, id)
            }
            targetDir?.deleteRecursively()
        }
    }

    suspend fun lookup(context: Context, id: String, word: String): List<StarDictEntry> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cachedDict = synchronized(lock) { cached[id] }
        val dict = cachedDict ?: run {
            val dir = directory(app, id)
            if (!dir.isDirectory) throw IOException("Dictionary missing on disk")
            // Validating a big index is slow, so it happens outside the lock the UI reads state under.
            val opened = StarDict(dir)
            synchronized(lock) {
                if (_state.value.packs.none { it.id == id }) throw IOException("Dictionary was removed")
                cached.clear()
                cached[id] = opened
            }
            opened
        }
        dict.lookup(word)
    }

    private fun isBusy(): Boolean {
        val op = _state.value.operation
        return op is Operation.Downloading || op is Operation.Unpacking
    }

    private suspend fun installPack(
        context: Context,
        explicitLanguage: String?,
        replaceLanguage: String?,
        open: () -> InputStream,
        onUnpacking: () -> Unit,
    ): Pack = withContext(Dispatchers.IO) {
        // Drop the cached index before validating a new pack, so two never sit in memory.
        synchronized(lock) { cached.clear() }
        val id = UUID.randomUUID().toString()
        val dir = directory(context, id)
        if (!dir.mkdirs()) throw IOException("Could not create dictionary directory")
        var committed = false
        try {
            val jobCtx = coroutineContext
            val starDict = open().use { stream ->
                StarDict.unpack(stream, dir) { jobCtx.ensureActive() }
            }
            onUnpacking()
            jobCtx.ensureActive()
            val resolvedLanguage = explicitLanguage?.ifBlank { null } ?: starDict.language
            if (resolvedLanguage.isNotBlank()) {
                File(dir, "language").writeText(resolvedLanguage)
            }
            val diskBytes = dir.walkTopDown().filter(File::isFile).sumOf(File::length)
            val newPack = Pack(
                id = id,
                name = starDict.name,
                language = resolvedLanguage,
                enabled = true,
                sizeBytes = diskBytes,
                wordCount = starDict.wordCount,
                isReaderDict = starDict.isReaderDict || explicitLanguage != null,
            )
            val replacedDirs: List<File>
            synchronized(lock) {
                val current = _state.value.packs
                val toReplace = current.filter { old ->
                    (replaceLanguage != null && old.isReaderDict && samePrimary(old.language, replaceLanguage)) ||
                        (!replaceLanguage.isNullOrBlank() && old.name == newPack.name && samePrimary(old.language, newPack.language)) ||
                        (replaceLanguage == null && old.name == newPack.name && old.language == newPack.language)
                }
                val kept = current.filterNot { it in toReplace }
                val updated = kept + newPack
                committed = savePacks(context, updated)
                if (!committed) throw IOException("Could not save dictionary list")
                toReplace.forEach { cached.remove(it.id) }
                cached.clear()
                cached[id] = starDict
                _state.value = _state.value.copy(packs = updated)
                replacedDirs = toReplace.map { directory(context, it.id) }
            }
            replacedDirs.forEach(File::deleteRecursively)
            newPack
        } finally {
            if (!committed) dir.deleteRecursively()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val packs = readPacks(context).filter { directory(context, it.id).isDirectory }
            // Nothing can be installing before the first load of this process, so any
            // directory not in the list is a leftover of an install the system killed.
            File(context.noBackupFilesDir, "dictionaries").listFiles()
                ?.filter { it.name !in packs.map(Pack::id) }
                ?.forEach(File::deleteRecursively)
            _state.value = State(packs = packs)
            loaded = true
        }
    }

    private fun readPacks(context: Context): List<Pack> {
        val prefs = preferences(context)
        val rawJson = prefs.getString(KEY_PACKS, null)
        if (rawJson != null) {
            return runCatching {
                val array = JSONArray(rawJson)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("id").takeIf(String::isNotBlank) ?: continue
                        add(
                            Pack(
                                id = id,
                                name = obj.optString("name"),
                                language = obj.optString("language"),
                                enabled = obj.optBoolean("enabled", true),
                                sizeBytes = obj.optLong("sizeBytes", 0L),
                                wordCount = obj.optInt("wordCount", 0),
                                isReaderDict = obj.optBoolean("isReaderDict", false),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        // Migrate single-dictionary preferences if present.
        val legacyId = prefs.getString("id", null) ?: return emptyList()
        val dir = directory(context, legacyId)
        if (!dir.isDirectory) return emptyList()
        val legacyName = prefs.getString("name", "").orEmpty()
        val legacyLang = prefs.getString("language", "").orEmpty()
        val legacySelected = prefs.getBoolean("selected", true)
        val diskBytes = dir.walkTopDown().filter(File::isFile).sumOf(File::length)
        val migrated = listOf(
            Pack(
                id = legacyId,
                name = legacyName,
                language = legacyLang,
                enabled = legacySelected,
                sizeBytes = diskBytes,
                wordCount = 0,
                isReaderDict = legacyName.startsWith("reader.dict", ignoreCase = true),
            ),
        )
        savePacks(context, migrated)
        return migrated
    }

    private fun savePacks(context: Context, packs: List<Pack>): Boolean {
        val array = JSONArray()
        for (pack in packs) {
            array.put(
                JSONObject()
                    .put("id", pack.id)
                    .put("name", pack.name)
                    .put("language", pack.language)
                    .put("enabled", pack.enabled)
                    .put("sizeBytes", pack.sizeBytes)
                    .put("wordCount", pack.wordCount)
                    .put("isReaderDict", pack.isReaderDict),
            )
        }
        return preferences(context).edit()
            .remove("id")
            .remove("name")
            .remove("language")
            .remove("selected")
            .putString(KEY_PACKS, array.toString())
            .commit()
    }

    internal fun samePrimary(a: String, b: String): Boolean {
        val pa = primaryLanguage(a)
        val pb = primaryLanguage(b)
        return pa.isNotBlank() && pa == pb
    }

    internal fun primaryLanguage(tag: String): String {
        val primary = tag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
        return when (primary) {
            "nb", "nn" -> "no"
            "fil" -> "tl"
            else -> primary
        }
    }

    private fun directory(context: Context, id: String) = File(context.noBackupFilesDir, "dictionaries/$id")
    private fun preferences(context: Context) = context.getSharedPreferences("offline_dictionary", Context.MODE_PRIVATE)

    private const val KEY_PACKS = "packs_json"
}

private class ProgressInputStream(
    delegate: InputStream,
    private val onProgress: (Long) -> Unit,
) : FilterInputStream(delegate) {
    private var totalRead = 0L
    private var lastReported = 0L

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) report(count.toLong())
        return count
    }

    override fun read(): Int {
        val byte = super.read()
        if (byte >= 0) report(1L)
        return byte
    }

    private fun report(delta: Long) {
        totalRead += delta
        if (totalRead - lastReported >= REPORT_STEP_BYTES) {
            lastReported = totalRead
            onProgress(totalRead)
        }
    }

    companion object {
        private const val REPORT_STEP_BYTES = 128L * 1024L
    }
}
