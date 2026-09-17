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
     * What the reader may start with.
     *
     * [POPULAR] is the whole catalog by download count. The rest are
     * Project Gutenberg's own bookshelves, which are curated by hand
     * and read far better than anything a subject search produces: a
     * search for the word "philosophy" leads with *The Picture of
     * Dorian Gray*, while the Philosophy bookshelf leads with *The
     * Prince*.
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
    enum class Category(private val shelf: Int?) {
        POPULAR(null),
        BEST_EVER(13),
        SCIENCE_FICTION(68),
        FANTASY(36),
        HORROR(42),
        GOTHIC(39),
        ADVENTURE(82),
        WESTERN(77),
        HISTORICAL_FICTION(41),
        MYSTERY(51),
        SHORT_STORIES(69),
        POETRY(60),
        HUMOR(44),
        PHILOSOPHY(57),
        CHILDRENS(20),
        ;

        /** The OPDS address this shelf is read from. */
        val url: String get() = shelf?.let { "$SHELF_BASE$it.opds" } ?: POPULAR_URL
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
