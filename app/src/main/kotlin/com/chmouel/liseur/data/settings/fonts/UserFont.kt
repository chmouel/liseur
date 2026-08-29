package com.chmouel.liseur.data.settings.fonts

import java.io.File

/**
 * A font the reader brought in themselves.
 *
 * Identity is the SHA-256 of the file, so the same file imported twice is
 * the same font, and — the part that matters — a font deleted and later
 * imported again comes back under the id every book already remembers.
 * Nothing about the name the file arrived with is load-bearing.
 *
 * [cssName] is namespaced rather than being the family's own name. An
 * imported font is free to call itself Literata, and without the prefix
 * it would quietly take over the bundled declaration, or a publisher's
 * own embedded face.
 */
data class UserFont(
    val digest: String,
    val displayName: String,
    val file: File,
    val extension: String,
    val italic: Boolean,
    val staticWeight: Int,
    val weightRange: IntRange?,
) {
    val id: String get() = ID_PREFIX + digest

    val cssName: String get() = "LiseurUser-$digest"

    val fileName: String get() = "$digest.$extension"

    companion object {
        const val ID_PREFIX = "user:"

        /** `ttf` or `otf`, decided by the file's own magic — never by its name. */
        val EXTENSIONS = setOf("ttf", "otf")

        private val DIGEST = Regex("[0-9a-f]{64}")

        /** Whether [digest] is one Liseur could have written. */
        fun isDigest(digest: String): Boolean = DIGEST.matches(digest)

        /**
         * The digest behind a stored id, or null if [id] is not one of ours.
         *
         * Deliberately strict: an id that is merely *unknown* has to stay
         * distinguishable from one that is malformed, so that a preference
         * written by some future version falls back to the default instead
         * of being read as a font that was never imported.
         */
        fun digestOf(id: String?): String? {
            if (id == null || !id.startsWith(ID_PREFIX)) return null
            return id.removePrefix(ID_PREFIX).takeIf(::isDigest)
        }

        /** The canonical file name for a digest, or null if either part is not canonical. */
        fun fileNameFor(digest: String, extension: String): String? =
            if (isDigest(digest) && extension in EXTENSIONS) "$digest.$extension" else null
    }
}
