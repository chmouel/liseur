package com.chmouel.liseur.data.opds

/**
 * A catalog of free books to start an empty library with.
 *
 * Liseur's first screen assumes the reader already has something: a
 * folder of EPUBs, one file, or a server to sign into. Somebody who
 * installed it to see what it is has none of the three, and nothing to
 * open. Project Gutenberg answers that: tens of thousands of books that
 * are out of copyright, given away, and served over plain OPDS with no
 * account to make.
 *
 * It is connected as an ordinary Custom server, anonymously. Nothing
 * here is a special case in the catalog layer; this file holds
 * addresses, and everything downstream of them treats those addresses
 * like any other the reader might have typed.
 *
 * Nothing here names a string to show. The data layer does not reach
 * for resources, so the labels live beside the card that draws them.
 */
object StarterCatalog {

    /**
     * Where a curated shelf lives.
     *
     * Note the missing trailing slash, which is not an oversight:
     * Gutenberg answers 403 to `bookshelf/<id>.opds/`, the exact
     * reverse of its `search.opds` route, which answers 403 *without*
     * one. `OpdsSetupClient` tries an address as typed before it tries
     * the other spelling, so both would go through — but a slash added
     * here would cost every connection a wasted request and a retry.
     */
    private const val SHELF_BASE = "https://www.gutenberg.org/ebooks/bookshelf/"
    private const val SEARCH_BASE = "https://www.gutenberg.org/ebooks/search.opds/"

    /**
     * Gutenberg's whole catalog, most-downloaded first.
     *
     * Deliberately not the root at `/ebooks.opds/`, whose three
     * navigation entries are Popular, Latest and Random and whose slice
     * of the catalog is therefore whatever happens to come first. This
     * one leads with the books somebody starting out would actually
     * want.
     */
    private const val POPULAR_URL =
        "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"

    /**
     * Languages with dedicated catalogs on Project Gutenberg.
     */
    val SUPPORTED_LANGUAGES = setOf(
        "fr", "de", "es", "it", "ru", "pt", "nl", "zh", "ja", "eo",
        "ca", "da", "fi", "hu", "pl", "sv",
    )

    /**
     * The language a shelf is in when nobody asked for another.
     *
     * Deliberately absent from [SUPPORTED_LANGUAGES], which lists the
     * languages that need a search address of their own: English is
     * what the curated shelves already are, so it is the one language
     * reached by *not* asking. A picker needs it back as something to
     * choose, which is what [OFFERED_LANGUAGES] is for.
     */
    const val DEFAULT_LANGUAGE = "en"

    /**
     * Every language a starting shelf may be asked for.
     *
     * A list rather than a set because this is what a reader is
     * offered, and an offer has an order. English leads it because it
     * is the one that always has books.
     */
    val OFFERED_LANGUAGES: List<String> = listOf(DEFAULT_LANGUAGE) + SUPPORTED_LANGUAGES

    /**
     * Which of [OFFERED_LANGUAGES] to open on, given the reader's own.
     *
     * A phone set to a language Gutenberg has a catalog for should not
     * have to say so, and one set to a language it does not should be
     * shown the shelf that has books rather than an empty one. Answered
     * here, once, rather than at whichever screen happens to ask.
     *
     * This does not decide an address — [Category.url] still does that,
     * and still applies its own rule to whatever it is handed.
     */
    fun resolveLanguage(preferred: String?): String =
        baseLanguage(preferred)?.takeIf { it in OFFERED_LANGUAGES } ?: DEFAULT_LANGUAGE

    /**
     * The primary subtag of a language tag, lowercased.
     *
     * Gutenberg catalogues by language and knows nothing of where it is
     * spoken: `pt-BR` and `pt-PT` are one collection, and `zh-Hans` is
     * the Chinese one. Android's `Locale.getLanguage()` hands over the
     * bare subtag already, so nothing in the app reaches this with a
     * region on it today — but a code is the sort of thing that arrives
     * from somewhere new, and reading `pt-BR` as "no Portuguese here"
     * would silently answer a reader in the wrong language.
     *
     * Both separators, because a tag is spelled with a hyphen and a
     * Java locale prints itself with an underscore.
     */
    internal fun baseLanguage(tag: String?): String? =
        tag?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() }

    /**
     * What the reader may start with.
     *
     * [POPULAR] is the catalog by download count. The rest are
     * Project Gutenberg's bookshelves or subject searches.
     *
     * In English, bookshelves curated by hand at Gutenberg are used
     * where available. For non-English languages, searches combining
     * subject and language (`s.<subject>+l.<lang>`) or the language's
     * most downloaded books are queried. If no books match, resolution
     * falls back to the English shelf.
     *
     * The ids are written down because there is no way to discover
     * them: Gutenberg publishes no OPDS index of its bookshelves. That
     * is the cost of the curation — an id Gutenberg retires becomes an
     * empty shelf, which is at least a legible failure rather than a
     * shelf of the wrong books.
     *
     * Each entry's address is its own account, because an `OpdsScope`
     * fingerprint takes in the path and the query. Changing category is
     * therefore an account switch, which is why the offer is only ever
     * made to a library with no server: it can make the first choice
     * and no other.
     */
    enum class Category(private val shelf: Int?, internal val subject: String? = null) {
        POPULAR(null),
        BEST_EVER(13),
        SCIENCE_FICTION(68, "science fiction"),
        FANTASY(36, "fantasy"),
        HORROR(42, "horror"),
        GOTHIC(39, "gothic"),
        ADVENTURE(82, "adventure"),
        WESTERN(77, "western"),
        HISTORICAL_FICTION(41, "historical fiction"),
        MYSTERY(51, "detective"),
        SHORT_STORIES(69, "short stories"),
        POETRY(60, "poetry"),
        HUMOR(44, "humor"),
        PHILOSOPHY(57, "philosophy"),
        CHILDRENS(20, "children"),
        ;

        /** English URL (curated shelf or default popular). */
        val englishUrl: String get() = shelf?.let { "$SHELF_BASE$it.opds" } ?: POPULAR_URL

        /**
         * Whether this shelf exists in any language but English.
         *
         * A curated bookshelf is a list somebody at Gutenberg wrote
         * down, and only "Best books ever" has no subject to search by
         * instead. Asked for in French it would have to fall back to
         * *something*, and the something it fell back to was the
         * language's most-downloaded feed — the identical address
         * "Most popular" already builds. Two chips, one account, no way
         * to tell from the shelf which one had been tapped.
         */
        val englishOnly: Boolean get() = shelf != null && subject == null

        /** Whether this shelf is worth offering to a reader of [languageCode]. */
        fun offeredIn(languageCode: String): Boolean =
            !englishOnly || baseLanguage(languageCode) == DEFAULT_LANGUAGE

        /**
         * The OPDS address for this category in [languageCode],
         * or [englishUrl] if languageCode is null, "en", or unsupported.
         *
         * An English-only shelf keeps its own English address rather
         * than borrowing another category's, so every category stays a
         * distinct account whatever language is asked for.
         */
        fun url(languageCode: String? = null): String {
            if (englishOnly) return englishUrl
            val lang = baseLanguage(languageCode)?.takeIf { it in SUPPORTED_LANGUAGES }
                ?: return englishUrl
            return if (subject != null) {
                "$SEARCH_BASE?query=s.${subject.replace(' ', '+')}+l.$lang&sort_order=downloads"
            } else {
                "$SEARCH_BASE?query=l.$lang&sort_order=downloads"
            }
        }

        /** The default English OPDS address for backwards compatibility. */
        val url: String get() = englishUrl
    }

    /**
     * How many books the offered shelf may hold.
     *
     * Bounded by what the walk can afford rather than by taste. A book
     * costs one request — Gutenberg lists each as a link to its own
     * feed — plus one more per page of twenty-five, against
     * `OpdsCatalogClient.MAX_REQUESTS`. Two hundred is a little over
     * half the budget and about a minute of asking somebody else's free
     * server for things, which is as far as a starting shelf should go.
     * Nothing here pretends to mirror a catalog of seventy thousand
     * books; `CatalogStatus.Partial` says so on the shelf itself.
     */
    val SIZES = listOf(25, 50, 100, 200)

    /**
     * How many books a shelf holds when nobody said.
     *
     * Enough to browse rather than to finish, and it arrives in well
     * under a minute.
     *
     * Nothing derives a size from an address. An address is not
     * ownership: a reader is free to type Gutenberg's most-downloaded
     * feed into the Book server form themselves, and reading that as a
     * shelf Liseur offered would cap their catalog at fifty books and
     * then, on the second refresh, reconcile everything past the
     * fiftieth as gone. A size is written down when the card makes the
     * connection, and a connection that has none is walked in full.
     */
    const val DEFAULT_SHELF = 50
}
