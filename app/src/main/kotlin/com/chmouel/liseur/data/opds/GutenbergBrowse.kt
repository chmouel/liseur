package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.BrowseCategory
import com.chmouel.liseur.data.remote.BrowseListing
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerKind

/** Extra navigation for Gutenberg's OPDS root, which only links Popular, Latest and Random. */
internal object GutenbergBrowse {
    const val LANGUAGES = "gutenberg:languages"
    const val CATEGORIES = "gutenberg:categories"
    private const val LANGUAGE_PREFIX = "gutenberg:language:"
    private const val SHELF_PREFIX = "gutenberg:shelf:"

    fun isRoot(server: RemoteServer): Boolean =
        server.kind == ServerKind.CUSTOM &&
            server.catalogUrl?.let { RemoteUrl.sameAddress(it, StarterCatalog.BROWSE_URL) } == true

    fun isVirtual(id: String): Boolean = id.startsWith("gutenberg:")

    fun rootCategories(): List<BrowseCategory> = listOf(
        BrowseCategory(LANGUAGES, "Languages"),
        BrowseCategory(CATEGORIES, "Categories"),
    )

    fun listing(id: String): BrowseListing? = when (id) {
        LANGUAGES -> BrowseListing(categories = StarterCatalog.OFFERED_LANGUAGES.map { code ->
            BrowseCategory("$LANGUAGE_PREFIX$code", code)
        })
        CATEGORIES -> shelves(StarterCatalog.DEFAULT_LANGUAGE)
        else -> languageCode(id)?.let(::shelves)
    }

    private fun shelves(language: String): BrowseListing = BrowseListing(
        categories = StarterCatalog.Category.entries
            .filter { it.offeredIn(language) }
            .map { BrowseCategory("$SHELF_PREFIX$language:${it.name}", it.name) },
    )

    fun languageCode(id: String): String? = id.takeIf { it.startsWith(LANGUAGE_PREFIX) }
        ?.removePrefix(LANGUAGE_PREFIX)
        ?.takeIf { it in StarterCatalog.OFFERED_LANGUAGES }

    fun shelfCategory(id: String): StarterCatalog.Category? {
        if (!id.startsWith(SHELF_PREFIX)) return null
        val parts = id.removePrefix(SHELF_PREFIX).split(':')
        if (parts.size != 2 || parts[0] !in StarterCatalog.OFFERED_LANGUAGES) return null
        return StarterCatalog.Category.entries.firstOrNull {
            it.name == parts[1] && it.offeredIn(parts[0])
        }
    }

    fun feedUrl(id: String): String? {
        val category = shelfCategory(id) ?: return null
        val language = id.removePrefix(SHELF_PREFIX).substringBefore(':')
        return category.url(language)
    }

    fun isShelf(id: String): Boolean = shelfCategory(id) != null
}
