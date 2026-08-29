package com.chmouel.liseur.data.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.chmouel.liseur.data.settings.fonts.FontNames
import com.chmouel.liseur.data.settings.fonts.SfntFont
import com.chmouel.liseur.data.settings.fonts.UserFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** What became of an attempt to bring a font in. */
sealed interface ImportResult {
    data class Imported(val id: String) : ImportResult
    data class AlreadyPresent(val id: String) : ImportResult
    data object NotAFont : ImportResult
    data object TooLarge : ImportResult
    data object TooMany : ImportResult
    data object Unreadable : ImportResult
    data object StorageFailed : ImportResult
}

/** What became of an attempt to take one away. */
sealed interface RemovalResult {
    data object Removed : RemovalResult
    data object InvalidId : RemovalResult
    data object NotFound : RemovalResult
    data object DeleteFailed : RemovalResult
    data object IndexFailed : RemovalResult
}

/**
 * The fonts the reader has imported.
 *
 * Content-addressed: a font is stored as `<sha256>.<ext>` and known by
 * `user:<sha256>`. That is what lets a font be deleted, and the same file
 * imported again months later, and every book that was reading in it pick
 * it straight back up — the id was never a handle into a table, it was
 * always the bytes.
 *
 * **The files are authoritative.** [index] caches only the display name,
 * because that is the one thing a font with no `name` table cannot tell us
 * a second time. Everything else is re-parsed on every scan, so a lost or
 * corrupted index costs a name and never a font.
 */
class UserFontRepository(
    context: Context,
    scope: CoroutineScope,
    /**
     * Injectable so tests can exercise a failing store without a mock
     * filesystem: a `renameTo` that returns false is a real branch with a
     * real recovery, and it needs to be reachable.
     */
    private val loadCheck: (File) -> Boolean = ::canBeLoaded,
) {
    private val resolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.filesDir, DIR)
    private val index = AtomicFile(File(dir, INDEX))

    private val _fonts = MutableStateFlow(emptyList<UserFont>())
    val fonts: StateFlow<List<UserFont>> = _fonts.asStateFlow()

    /**
     * One lock over the scan as well as the mutations.
     *
     * Without it covering the startup scan, a reader quick enough to
     * import a font before the scan finished would watch it disappear:
     * the scan would publish a directory listing taken before the import
     * over the top of it.
     */
    private val lock = Mutex()

    private val _ready = MutableStateFlow(false)

    init {
        scope.launch { lock.withLock { rescanLocked() }; _ready.value = true }
    }

    /**
     * Suspends until the first scan has published.
     *
     * The reader calls this before taking its first font snapshot. A book
     * already set to an imported font would otherwise open in the default
     * for a beat and then rebuild its navigator when the scan landed — a
     * reflow, unasked for, on the screen someone was trying to read.
     */
    suspend fun awaitReady() {
        if (_ready.value) return
        _ready.first { it }
    }

    /** The ids currently backed by a file, for resolving a stored preference. */
    fun registry(): Set<String> = _fonts.value.mapTo(HashSet()) { it.id }

    // -- import -------------------------------------------------------------

    suspend fun import(uri: Uri, pickedName: String?): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock { importLocked(uri, pickedName) }
    }

    private fun importLocked(uri: Uri, pickedName: String?): ImportResult {
        if (!dir.exists() && !dir.mkdirs()) return ImportResult.StorageFailed

        val temp = File(dir, "${UUID.randomUUID()}$TEMP")
        val staged = try {
            stage(uri, temp)
        } catch (e: IOException) {
            Log.w(TAG, "could not read the picked font", e)
            temp.delete()
            return ImportResult.Unreadable
        } catch (e: SecurityException) {
            // A provider can withdraw a grant between the pick and the read.
            Log.w(TAG, "no longer permitted to read the picked font", e)
            temp.delete()
            return ImportResult.Unreadable
        }

        val outcome = when (staged) {
            is Staged.Hashed -> staged
            is Staged.Refused -> {
                temp.delete()
                return staged.result
            }
        }

        // The staged file was written a moment ago, but the storage it went
        // to can still fail underneath us, and a throw here would escape the
        // import as a crash rather than a message.
        val bytes = try {
            temp.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "could not read back the staged font", e)
            temp.delete()
            return ImportResult.Unreadable
        }

        val metadata = SfntFont.parse(bytes)
        if (metadata == null || !loadCheck(temp)) {
            // Plausible sfnt tables are not the same thing as a font this
            // device can actually render, and one that fails to load would
            // otherwise reach the dropdown's preview and throw inside
            // composition.
            temp.delete()
            return ImportResult.NotAFont
        }

        val target = File(dir, "${outcome.digest}.${metadata.format.extension}")
        val id = UserFont.ID_PREFIX + outcome.digest

        // Dedupe before the cap, deliberately: re-picking a font already
        // imported must land on it, never be refused for a limit it does
        // not push against.
        if (target.exists()) {
            temp.delete()
            return ImportResult.AlreadyPresent(id)
        }
        if (_fonts.value.size >= MAX_FONTS) {
            temp.delete()
            return ImportResult.TooMany
        }

        if (!temp.renameTo(target)) {
            temp.delete()
            return ImportResult.StorageFailed
        }

        val name = metadata.familyName
            ?: pickedName?.substringBeforeLast('.')?.let(FontNames::sanitize)
            ?: FontNames.fallbackName(outcome.digest)

        return try {
            writeIndex(readIndex() + (id to name))
            rescanLocked()
            ImportResult.Imported(id)
        } catch (e: IOException) {
            Log.w(TAG, "font stored but the name index could not be written", e)
            // The file is there and must be published as there. It comes
            // back with a name derived from its own tables or its digest;
            // the list never disagrees with the directory.
            rescanLocked()
            ImportResult.Imported(id)
        }
    }

    private sealed interface Staged {
        data class Hashed(val digest: String) : Staged
        data class Refused(val result: ImportResult) : Staged
    }

    /**
     * Copies [uri] into [temp], hashing as it goes.
     *
     * The size cap is enforced *while* streaming rather than by asking the
     * provider how big the file is: the answer is optional, and a
     * mis-picked 2 GB video should be abandoned in the first megabyte
     * rather than after it has been copied into private storage.
     */
    private fun stage(uri: Uri, temp: File): Staged {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = resolver.openInputStream(uri)
            ?: return Staged.Refused(ImportResult.Unreadable)
        input.use { source ->
            temp.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return Staged.Refused(ImportResult.TooLarge)
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                }
            }
        }
        if (total == 0L) return Staged.Refused(ImportResult.NotAFont)
        return Staged.Hashed(digest.digest().joinToString("") { "%02x".format(it) })
    }

    // -- removal ------------------------------------------------------------

    suspend fun remove(id: String): RemovalResult = withContext(Dispatchers.IO) {
        lock.withLock { removeLocked(id) }
    }

    private fun removeLocked(id: String): RemovalResult {
        if (UserFont.digestOf(id) == null) return RemovalResult.InvalidId
        // Resolved through the registry, never by building a path out of
        // what the caller passed in.
        val font = _fonts.value.firstOrNull { it.id == id } ?: return RemovalResult.NotFound

        if (font.file.exists() && !font.file.delete()) return RemovalResult.DeleteFailed

        return try {
            writeIndex(readIndex() - id)
            rescanLocked()
            RemovalResult.Removed
        } catch (e: IOException) {
            Log.w(TAG, "font deleted but the name index could not be written", e)
            // The file is gone; the list has to say so even though the
            // index still names it. The scan drops entries with no file.
            rescanLocked()
            RemovalResult.IndexFailed
        }
    }

    // -- scanning -----------------------------------------------------------

    private fun rescanLocked() {
        val names = try {
            readIndex()
        } catch (e: IOException) {
            Log.w(TAG, "name index unreadable; falling back to what the files say", e)
            emptyMap()
        }

        val files = dir.listFiles().orEmpty()
        val found = ArrayList<UserFont>(files.size)

        for (file in files) {
            if (file.name.startsWith(INDEX)) continue
            if (file.name.endsWith(TEMP)) {
                // A staged import that died before its rename. Nothing
                // refers to it and nothing ever will.
                file.delete()
                continue
            }

            val digest = file.nameWithoutExtension
            val extension = file.extension.lowercase(Locale.ROOT)
            if (UserFont.fileNameFor(digest, extension) != file.name) continue

            // A font that has become unreadable — storage detached, a
            // directory where a file was — costs itself and not the scan.
            // This runs from `init`, where a throw would take the whole
            // shelf down and never come back.
            val bytes = try {
                file.readBytes()
            } catch (e: IOException) {
                Log.w(TAG, "could not read ${file.name}; leaving it out", e)
                continue
            }
            val metadata = SfntFont.parse(bytes) ?: continue
            // A file whose magic disagrees with the name Liseur gave it is
            // not one Liseur wrote.
            if (metadata.format.extension != extension) continue

            val id = UserFont.ID_PREFIX + digest
            found += UserFont(
                digest = digest,
                displayName = names[id]
                    ?: metadata.familyName
                    ?: FontNames.fallbackName(digest),
                file = file,
                extension = extension,
                italic = metadata.italic,
                staticWeight = metadata.weight,
                weightRange = metadata.weightRange,
            )
        }

        // Locale.ROOT, not the device's: a Turkish reader's dotted and
        // dotless i would otherwise order their shelf differently from
        // everyone else's, and this fold exists only to sort.
        _fonts.value = found.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    // -- the index ----------------------------------------------------------

    private fun readIndex(): Map<String, String> {
        if (!index.baseFile.exists()) return emptyMap()
        val text = index.readFully().toString(Charsets.UTF_8)
        return try {
            val json = JSONObject(text)
            buildMap {
                for (key in json.keys()) {
                    val name = json.optString(key).takeIf { it.isNotEmpty() } ?: continue
                    // Sanitised on the way back in as well as on the way
                    // out: the file is only as trustworthy as whatever
                    // last wrote it, and a restore could have brought it
                    // from another install.
                    FontNames.sanitize(name)?.let { put(key, it) }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "name index is not readable JSON; ignoring it", e)
            emptyMap()
        }
    }

    /**
     * Writes through [AtomicFile] rather than a bare rename.
     *
     * `File.renameTo` says nothing about replacing an existing target, and
     * a half-written index read back as JSON would lose every name at
     * once.
     */
    private fun writeIndex(names: Map<String, String>) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("no font directory")
        val json = JSONObject()
        names.forEach { (id, name) -> json.put(id, name) }
        val stream = index.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (e: IOException) {
            index.failWrite(stream)
            throw e
        }
    }

    companion object {
        private const val TAG = "UserFonts"

        const val DIR = "fonts"

        /**
         * `AtomicFile` writes alongside the base name, so its working
         * files share this prefix. Skipping by prefix rather than naming
         * each suffix means a platform that adds another one does not
         * quietly put a stray file in front of the parser.
         */
        private const val INDEX = "index.json"
        private const val TEMP = ".tmp"

        private const val BUFFER = 64 * 1024
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val MAX_FONTS = 32

        /**
         * Whether Android itself can make a [Typeface] of this file.
         *
         * The WebView and the Compose preview both go through the same
         * loader, so a file that fails here would fail on the page and in
         * the dropdown, and the dropdown's failure is inside composition.
         */
        private fun canBeLoaded(file: File): Boolean = try {
            Typeface.Builder(file).build() != null
        } catch (e: RuntimeException) {
            Log.w(TAG, "the platform refused to load the picked font", e)
            false
        }
    }
}
