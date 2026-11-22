package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.WorkIdentifiers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What came of trying a Hardcover token. */
sealed interface HardcoverConnectOutcome {
    data class Connected(val username: String) : HardcoverConnectOutcome
    data class Failed(val reason: SyncFailure) : HardcoverConnectOutcome
}

/** What came of reporting one book to Hardcover. */
sealed interface HardcoverPushOutcome {
    data object Pushed : HardcoverPushOutcome

    /** Hardcover already says what this device would have said. */
    data object UpToDate : HardcoverPushOutcome

    /** No account, or its token cannot be read back on this phone. */
    data object NotConnected : HardcoverPushOutcome

    /** The reader keeps this book, or automatic pushes, switched off. */
    data object SwitchedOff : HardcoverPushOutcome

    /** No edition is chosen and nothing certain enough was found. */
    data object NeedsMatch : HardcoverPushOutcome

    data class Failed(val reason: SyncFailure) : HardcoverPushOutcome
}

/**
 * The Hardcover account, which edition each book is, and the push that
 * reports reading.
 *
 * Reporting is one way: nothing Hardcover holds moves anything here, so
 * this is no position peer and never takes part in a merge. A push reads
 * Hardcover's side first and sends only the difference ([HardcoverPlan]),
 * so running it twice writes nothing the second time.
 *
 * Pushes run one at a time under [lock]. Two at once for the same
 * book would both see it missing from the library and add it twice.
 * Account and match changes take the same lock, so a push that is still
 * waiting on Hardcover cannot write for an account or a book file that
 * has since been replaced.
 */
class HardcoverRepository(
    private val dao: HardcoverDao,
    private val bookFor: suspend (String) -> Book?,
    private val localFor: suspend (String) -> LocalReading?,
    private val client: HardcoverClient = HardcoverClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val seal: (String) -> String = HardcoverAccount::seal,
    private val open: (HardcoverAccount) -> String? = { it.token },
) {
    private val lock = Mutex()

    val account: Flow<HardcoverAccount?> = dao.observeAccount()

    fun link(bookUrl: String): Flow<HardcoverBookLink?> = dao.observeLink(bookUrl)

    /** Proves a token and keeps it. Nothing is stored for a token Hardcover refuses. */
    suspend fun connect(token: String): HardcoverConnectOutcome = lock.withLock {
        val bare = HardcoverClient.bearer(token)
        if (bare.isEmpty()) return@withLock HardcoverConnectOutcome.Failed(SyncFailure.Unauthorised)
        // Under the lock, so a disconnect or restore while Hardcover answers is not undone.
        val user = when (val asked = client.me(bare)) {
            is RemoteResult.Ok -> asked.value
            is RemoteResult.Failed -> return@withLock HardcoverConnectOutcome.Failed(asked.reason)
        }
        inTransaction {
            // Another person's choices about what stays off Hardcover are not this account's.
            val owner = dao.linksOwner()
            if (owner != null && owner != user.id) {
                dao.deleteLinks()
                dao.clearPending()
            }
            dao.setLinksOwner(HardcoverLinksOwner(userId = user.id))
            val old = dao.account()
            dao.upsertAccount(
                HardcoverAccount(
                    userId = user.id,
                    username = user.username,
                    tokenCipher = seal(bare),
                    addedAt = now(),
                    autoSync = old?.autoSync ?: true,
                ),
            )
        }
        HardcoverConnectOutcome.Connected(user.username)
    }

    suspend fun current(): HardcoverAccount? = dao.account()

    /**
     * Puts back an account from a backup without asking Hardcover: a
     * restore may run offline, and a token that no longer works says so
     * on the first push. Matches made under another account go.
     */
    suspend fun restoreBackup(account: HardcoverAccount?) = lock.withLock {
        inTransaction {
            if (account == null || dao.linksOwner() != account.userId) dao.deleteLinks()
            dao.deleteAccount()
            dao.clearPending()
            if (account == null) {
                dao.deleteLinksOwner()
            } else {
                dao.upsertAccount(account.copy(addedAt = now()))
                dao.setLinksOwner(HardcoverLinksOwner(userId = account.userId))
            }
        }
    }

    /**
     * Forgets the account and what was queued for it. The matches stay,
     * so signing back in to the same account keeps the books chosen and
     * the ones kept off; signing in to another account drops them.
     */
    suspend fun disconnect() = lock.withLock {
        inTransaction {
            dao.deleteAccount()
            dao.clearPending()
        }
    }

    suspend fun setAutoSync(autoSync: Boolean) = dao.setAutoSync(autoSync)

    /** Keeps one book off Hardcover, or lets it back on. */
    suspend fun setExcluded(bookUrl: String, excluded: Boolean) = lock.withLock {
        val link = dao.link(bookUrl) ?: HardcoverBookLink(bookUrl = bookUrl)
        dao.upsertLink(link.copy(excluded = excluded))
    }

    /**
     * Queues a book for the next drain, when an automatic push is wanted.
     * Answers whether anything was queued.
     */
    suspend fun queue(bookUrl: String): Boolean = lock.withLock {
        val account = dao.account() ?: return@withLock false
        if (!account.autoSync || account.tokenRefused || dao.link(bookUrl)?.excluded == true) return@withLock false
        dao.queue(HardcoverPending(bookUrl, now()))
        true
    }

    suspend fun remembers(bookUrl: String): Boolean = dao.link(bookUrl) != null

    suspend fun nextPending(): HardcoverPending? = dao.nextPending()

    suspend fun settle(pending: HardcoverPending) = dao.settle(pending.bookUrl, pending.queuedAt)

    /** Forgets books gone from this device, or whose file now holds another book. */
    suspend fun forgetBooks(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        lock.withLock {
            inTransaction {
                bookUrls.chunked(SQL_BATCH).forEach { batch ->
                    dao.forgetLinks(batch)
                    dao.forgetPending(batch)
                    dao.unmarkForgetting(batch)
                }
            }
        }
    }

    /**
     * Marks books [forgetBooks] is about to drop, from inside the library
     * transaction removing them: until it settles, a push would report a
     * replaced file under the old book's match.
     */
    suspend fun willForget(bookUrls: List<String>) {
        dao.markForgetting(bookUrls.map(::HardcoverForgetting))
    }

    /** Lifts [willForget] for books whose removal was rolled back. */
    suspend fun keep(bookUrls: List<String>) {
        bookUrls.chunked(SQL_BATCH).forEach { dao.unmarkForgetting(it) }
    }

    /**
     * Settles removals a stopped process left marked. Whether they
     * committed is unknown, so their matches go: a book matched again is
     * cheaper than a replaced file reported under the old book.
     */
    suspend fun settleForgetting() = forgetBooks(dao.forgetting())

    /**
     * The editions this book could be, for the reader to choose from: by
     * the file's ISBN when it has one, else the editions of the best
     * title-and-author match.
     */
    suspend fun candidates(bookUrl: String): RemoteResult<List<HardcoverEdition>> {
        val token = dao.account()?.let(open) ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val book = bookFor(bookUrl) ?: return RemoteResult.Ok(emptyList())
        when (val byIsbn = editionsByIsbn(token, book)) {
            is RemoteResult.Failed -> return byIsbn
            is RemoteResult.Ok -> if (byIsbn.value.isNotEmpty()) return byIsbn
        }
        val found = when (val searched = search(token, book)) {
            is RemoteResult.Failed -> return searched
            is RemoteResult.Ok ->
                HardcoverMatch.confident(book.matchTitle, book.matchAuthor, searched.value)?.let(::listOf)
                    ?: searched.value.take(PICKER_HITS)
        }
        if (found.isEmpty()) return RemoteResult.Ok(emptyList())
        return client.editionsOfBooks(token, found.map { it.id })
    }

    /** Remembers which edition a book is. */
    suspend fun choose(bookUrl: String, edition: HardcoverEdition) = lock.withLock {
        val link = dao.link(bookUrl) ?: HardcoverBookLink(bookUrl = bookUrl)
        // The same Hardcover book keeps the rating this device set there and a finish still owed.
        val sameBook = link.hardcoverBookId == edition.bookId
        dao.upsertLink(
            link.matchedWith(edition).copy(
                pushedAt = null,
                pushedRating = link.pushedRating.takeIf { sameBook },
                datesPending = link.datesPending && sameBook,
                editionChosen = true,
            ),
        )
    }

    /**
     * Reports where the reader is in one book.
     *
     * [asked] is true when the reader pressed the button: that overrides
     * the automatic-sync setting, but not the per-book switch.
     */
    suspend fun push(bookUrl: String, asked: Boolean): HardcoverPushOutcome = lock.withLock {
        pushNoting(bookUrl, asked)
    }

    /**
     * Pushes the oldest queued book. It is picked under the same lock as
     * account changes, so a queue cleared for another account is never
     * sent with the new token.
     */
    suspend fun pushNext(): Pair<HardcoverPending, HardcoverPushOutcome>? = lock.withLock {
        val pending = dao.nextPending() ?: return@withLock null
        pending to pushNoting(pending.bookUrl, asked = false)
    }

    private suspend fun pushNoting(bookUrl: String, asked: Boolean): HardcoverPushOutcome {
        val outcome = pushLocked(bookUrl, asked)
        val reason = (outcome as? HardcoverPushOutcome.Failed)?.reason
        if (reason == SyncFailure.Unauthorised || reason == SyncFailure.Forbidden) dao.setTokenRefused(true)
        return outcome
    }

    private suspend fun pushLocked(bookUrl: String, asked: Boolean): HardcoverPushOutcome {
        val account = dao.account() ?: return HardcoverPushOutcome.NotConnected
        val token = open(account) ?: return HardcoverPushOutcome.NotConnected
        if (!asked && !account.autoSync) return HardcoverPushOutcome.SwitchedOff
        // Asking again with a token Hardcover refused would only spend requests; the reader must fix it.
        if (!asked && account.tokenRefused) return HardcoverPushOutcome.Failed(SyncFailure.Unauthorised)
        if (dao.isForgetting(bookUrl)) return HardcoverPushOutcome.UpToDate
        var link = dao.link(bookUrl)
        if (link?.excluded == true) return HardcoverPushOutcome.SwitchedOff
        val local = localFor(bookUrl) ?: return HardcoverPushOutcome.UpToDate
        // A removal marks the book before it commits, so a mark now means the read may hold its replacement.
        if (dao.isForgetting(bookUrl)) return HardcoverPushOutcome.UpToDate

        if (link?.matched != true) {
            // Nothing to say about a book never opened, rated or chosen; no search for it either.
            if (HardcoverPlan.targetStatus(local) == null && local.rating == null) {
                return HardcoverPushOutcome.UpToDate
            }
            val book = bookFor(bookUrl) ?: return HardcoverPushOutcome.NeedsMatch
            if (dao.isForgetting(bookUrl)) return HardcoverPushOutcome.UpToDate
            val edition = when (val found = autoMatch(token, book)) {
                is RemoteResult.Failed -> return HardcoverPushOutcome.Failed(found.reason)
                is RemoteResult.Ok -> found.value ?: return HardcoverPushOutcome.NeedsMatch
            }
            link = (link ?: HardcoverBookLink(bookUrl = bookUrl)).matchedWith(edition)
            dao.upsertLink(link)
        }
        val bookId = link.hardcoverBookId ?: return HardcoverPushOutcome.NeedsMatch
        val editionId = link.editionId ?: return HardcoverPushOutcome.NeedsMatch

        var shelf = when (val asking = client.shelfState(token, account.userId, bookId)) {
            is RemoteResult.Failed -> return HardcoverPushOutcome.Failed(asking.reason)
            is RemoteResult.Ok -> asking.value
        }
        // Only an answer from Hardcover proves the token works again.
        if (account.tokenRefused) dao.setTokenRefused(false)
        val entry = HardcoverPlan.entryStep(shelf, local, link.pushedRating, editionId.takeIf { link.editionChosen })
        val status = HardcoverPlan.statusAfter(shelf, entry)
        val statusChanged = status != shelf.statusId
        val ratingNow = when (entry) {
            is HardcoverEntryStep.Add -> entry.rating
            is HardcoverEntryStep.Update -> when {
                entry.rating != null -> entry.rating
                entry.clearRating -> null
                else -> link.pushedRating
            }
            null -> link.pushedRating
        }
        if (statusChanged && status == HardcoverStatus.READ) dao.markDatesPending(bookUrl)
        val sent = when (entry) {
            is HardcoverEntryStep.Add -> client.addToLibrary(token, bookId, editionId, entry.statusId, entry.rating)
            is HardcoverEntryStep.Update -> client.updateEntry(token, checkNotNull(shelf.userBookId), entry)
            null -> null
        }
        if (sent is RemoteResult.Failed) return HardcoverPushOutcome.Failed(sent.reason)
        var wrote = entry != null
        // Kept now, so a read update failing below cannot lose which rating this device set.
        if (wrote && ratingNow != link.pushedRating) dao.setPushedRating(bookUrl, ratingNow)
        if (statusChanged) {
            // The read a status change starts or finishes exists only on the server.
            shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                is RemoteResult.Failed -> return HardcoverPushOutcome.Failed(again.reason)
                is RemoteResult.Ok -> again.value
            }
        }
        val justFinished = status == HardcoverStatus.READ && (statusChanged || link.datesPending)
        val read = HardcoverPlan.readStep(
            status, shelf.read, local, link.editionPages, justFinished, editionId, link.editionChosen,
        )
        read?.let { step ->
            val sent = client.updateRead(token, checkNotNull(shelf.read).id, step)
            if (sent is RemoteResult.Failed) return HardcoverPushOutcome.Failed(sent.reason)
            wrote = true
        }
        dao.setPushed(bookUrl, now(), ratingNow)
        return if (wrote) HardcoverPushOutcome.Pushed else HardcoverPushOutcome.UpToDate
    }

    /**
     * The edition this book is, found without asking, or null when nothing
     * is certain enough: by ISBN, else a search hit agreeing on title and
     * author, reported against Hardcover's default edition for it.
     */
    private suspend fun autoMatch(token: String, book: Book): RemoteResult<HardcoverEdition?> {
        when (val byIsbn = editionsByIsbn(token, book)) {
            is RemoteResult.Failed -> return byIsbn
            is RemoteResult.Ok -> byIsbn.value.firstOrNull()?.let { return RemoteResult.Ok(it) }
        }
        val hit = when (val searched = search(token, book)) {
            is RemoteResult.Failed -> return searched
            is RemoteResult.Ok ->
                HardcoverMatch.confident(book.matchTitle, book.matchAuthor, searched.value)
                    ?: return RemoteResult.Ok(null)
        }
        return when (val defaults = client.defaultEditions(token, hit.id)) {
            is RemoteResult.Failed -> defaults
            is RemoteResult.Ok -> RemoteResult.Ok(HardcoverMatch.preferredEdition(defaults.value))
        }
    }

    private suspend fun search(token: String, book: Book): RemoteResult<List<HardcoverBook>> {
        val query = listOfNotNull(book.matchTitle, book.matchAuthor).joinToString(" ").trim()
        if (query.isEmpty()) return RemoteResult.Ok(emptyList())
        return client.searchBooks(token, query)
    }

    /** The editions carrying the file's ISBN, or none when its identifier is no ISBN. */
    private suspend fun editionsByIsbn(token: String, book: Book): RemoteResult<List<HardcoverEdition>> {
        val isbn = Isbn.from(WorkIdentifiers.dcFrom(book.workId, book.stableTitle, book.identityAuthor ?: book.stableAuthor))
            ?: return RemoteResult.Ok(emptyList())
        return client.editionsByIsbn(token, isbn)
    }

    private fun HardcoverBookLink.matchedWith(edition: HardcoverEdition) = copy(
        hardcoverBookId = edition.bookId,
        editionId = edition.id,
        editionTitle = edition.title.ifBlank { edition.bookTitle },
        editionPages = edition.pages,
    )

    private companion object {
        /** Search hits whose editions the picker offers when none is a sure match. */
        const val PICKER_HITS = 5
        private const val SQL_BATCH = 500
    }
}

// The file's or catalogue's own title and author, as work identity uses
// them: a renamed book must not be matched to whatever its new name finds.
private val Book.matchTitle: String? get() = stableTitle ?: title
private val Book.matchAuthor: String? get() = stableAuthor ?: author
