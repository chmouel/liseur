package com.chmouel.liseur.data.hardcover

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.HardcoverAccount
import com.chmouel.liseur.data.db.HardcoverBookLink
import com.chmouel.liseur.data.db.HardcoverDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.HardcoverProgress
import com.chmouel.liseur.domain.HardcoverShelfState
import com.chmouel.liseur.domain.HardcoverStatus
import com.chmouel.liseur.domain.HardcoverStep
import com.chmouel.liseur.domain.Isbn
import com.chmouel.liseur.domain.WorkIdentifiers
import com.chmouel.liseur.domain.readingStatusFor
import kotlinx.coroutines.flow.Flow

/** What came of trying a Hardcover token. */
sealed interface HardcoverConnectOutcome {
    data class Connected(val username: String) : HardcoverConnectOutcome
    data class Failed(val reason: SyncFailure) : HardcoverConnectOutcome
}

/** What came of reporting one book to Hardcover. */
sealed interface HardcoverPushOutcome {
    /** Something was written. */
    data object Pushed : HardcoverPushOutcome

    /** Hardcover already says what this device would have said. */
    data object UpToDate : HardcoverPushOutcome

    /** No account, or its token cannot be read back on this phone. */
    data object NotConnected : HardcoverPushOutcome

    /** The reader keeps this book, or automatic pushes, switched off. */
    data object SwitchedOff : HardcoverPushOutcome

    /** No edition is chosen and the file carries no ISBN to choose by. */
    data object NeedsMatch : HardcoverPushOutcome

    data class Failed(val reason: SyncFailure) : HardcoverPushOutcome
}

/**
 * The Hardcover account, the match between books here and editions
 * there, and the push that reports reading.
 *
 * Reporting is one way. Hardcover is told where the reader is; nothing
 * here moves a reading position because of what Hardcover holds, so
 * this is not a `PositionSync` peer and never takes part in a merge.
 *
 * A push reads Hardcover's side first and sends only the difference
 * ([HardcoverProgress.plan]), so running it twice, or after the same
 * thing was done on the website, writes nothing and adds no journal
 * entry.
 */
class HardcoverRepository(
    private val dao: HardcoverDao,
    private val bookFor: suspend (String) -> Book?,
    private val progressFor: suspend (String) -> ReadingProgress?,
    private val client: HardcoverClient = HardcoverClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Sealing and opening the token, apart so the rules here run without a Keystore. */
    private val seal: (String) -> String = HardcoverAccount::seal,
    private val open: (HardcoverAccount) -> String? = { it.token },
) {

    val account: Flow<HardcoverAccount?> = dao.observeAccount()

    fun link(bookUrl: String): Flow<HardcoverBookLink?> = dao.observeLink(bookUrl)

    /**
     * Proves a token and keeps it. Nothing is stored for a token
     * Hardcover does not accept.
     */
    suspend fun connect(token: String): HardcoverConnectOutcome {
        val bare = HardcoverClient.bearer(token)
        if (bare.isEmpty()) return HardcoverConnectOutcome.Failed(SyncFailure.Unauthorised)
        val user = when (val asked = client.me(bare)) {
            is RemoteResult.Ok -> asked.value
            is RemoteResult.Failed -> return HardcoverConnectOutcome.Failed(asked.reason)
        }
        inTransaction {
            // Another person's choices about what stays off Hardcover
            // are not this account's.
            val old = dao.account()
            if (old != null && old.userId != user.id) dao.deleteLinks()
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
        return HardcoverConnectOutcome.Connected(user.username)
    }

    suspend fun current(): HardcoverAccount? = dao.account()

    /**
     * Puts back an account from a settings backup, without asking
     * Hardcover: a restore may run offline, and a token that no longer
     * works says so on the first push. Matches made under another
     * account go, as they do on a fresh sign-in.
     */
    internal suspend fun restoreBackup(account: HardcoverAccount?) {
        inTransaction {
            val old = dao.account()
            if (old != null && old.userId != account?.userId) dao.deleteLinks()
            dao.deleteAccount()
            if (account != null) dao.upsertAccount(account.copy(addedAt = now()))
        }
    }

    /** Forgets the account and every match made under it. */
    suspend fun disconnect() {
        inTransaction {
            dao.deleteAccount()
            dao.deleteLinks()
        }
    }

    suspend fun setAutoSync(autoSync: Boolean) = dao.setAutoSync(autoSync)

    /** Keeps one book off Hardcover, or lets it back on. */
    suspend fun setExcluded(bookUrl: String, excluded: Boolean) {
        val link = dao.link(bookUrl) ?: HardcoverBookLink(bookUrl = bookUrl)
        dao.upsertLink(link.copy(excluded = excluded))
    }

    /** Whether closing this book should report it without being asked. */
    suspend fun pushesOnClose(bookUrl: String): Boolean {
        val account = dao.account() ?: return false
        return account.autoSync && dao.link(bookUrl)?.excluded != true
    }

    /**
     * The editions this book could be, for the reader to choose from.
     *
     * By the file's own ISBN when it has one; otherwise the editions of
     * the best title-and-author match. An empty list is an honest
     * answer: Hardcover does not know the book under either.
     */
    suspend fun candidates(bookUrl: String): RemoteResult<List<HardcoverEdition>> {
        val token = dao.account()?.let(open) ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val book = bookFor(bookUrl) ?: return RemoteResult.Ok(emptyList())
        isbnOf(book)?.let { isbn ->
            when (val byIsbn = client.editionsByIsbn(token, isbn)) {
                is RemoteResult.Failed -> return byIsbn
                is RemoteResult.Ok -> if (byIsbn.value.isNotEmpty()) return byIsbn
            }
        }
        val found = when (val searched = search(token, book)) {
            is RemoteResult.Failed -> return searched
            // The confident match first, so the list offered is that
            // book's editions; otherwise whatever ranked best.
            is RemoteResult.Ok ->
                HardcoverMatch.confident(book.title, book.author, searched.value)
                    ?: searched.value.firstOrNull()
                    ?: return RemoteResult.Ok(emptyList())
        }
        return client.editionsOfBook(token, found.id)
    }

    /**
     * The edition this book is, found without asking the reader, or null
     * when nothing is certain enough to report against.
     *
     * By ISBN when the file has one Hardcover knows; otherwise by a
     * title-and-author search whose best hit agrees on both
     * ([HardcoverMatch.confident]), reported against the edition Hardcover
     * shows for that book by default.
     */
    private suspend fun autoMatch(token: String, book: Book): RemoteResult<HardcoverEdition?> {
        isbnOf(book)?.let { isbn ->
            when (val byIsbn = client.editionsByIsbn(token, isbn)) {
                is RemoteResult.Failed -> return byIsbn
                is RemoteResult.Ok -> byIsbn.value.firstOrNull()?.let { return RemoteResult.Ok(it) }
            }
        }
        val hit = when (val searched = search(token, book)) {
            is RemoteResult.Failed -> return searched
            is RemoteResult.Ok ->
                HardcoverMatch.confident(book.title, book.author, searched.value)
                    ?: return RemoteResult.Ok(null)
        }
        return when (val defaults = client.defaultEditions(token, hit.id)) {
            is RemoteResult.Failed -> defaults
            is RemoteResult.Ok -> RemoteResult.Ok(HardcoverMatch.preferredEdition(defaults.value))
        }
    }

    private suspend fun search(token: String, book: Book): RemoteResult<List<HardcoverBook>> {
        val query = listOfNotNull(book.title, book.author).joinToString(" ").trim()
        if (query.isEmpty()) return RemoteResult.Ok(emptyList())
        return client.searchBooks(token, query)
    }

    /** Remembers which edition a book is. */
    suspend fun choose(bookUrl: String, edition: HardcoverEdition) {
        val link = dao.link(bookUrl) ?: HardcoverBookLink(bookUrl = bookUrl)
        dao.upsertLink(link.matchedWith(edition))
    }

    /** Forgets the match, so the next push or the reader chooses again. */
    suspend fun unmatch(bookUrl: String) {
        val link = dao.link(bookUrl) ?: return
        dao.upsertLink(
            link.copy(
                hardcoverBookId = null, editionId = null, editionTitle = null,
                editionPages = null, pushedAt = null,
            ),
        )
    }

    /**
     * Reports where the reader is in one book.
     *
     * [asked] is true when the reader pressed the button: that overrides
     * the automatic-sync setting, but not the per-book switch, which
     * says this book is not Hardcover's business at all.
     */
    suspend fun push(bookUrl: String, asked: Boolean): HardcoverPushOutcome {
        val account = dao.account() ?: return HardcoverPushOutcome.NotConnected
        val token = open(account) ?: return HardcoverPushOutcome.NotConnected
        if (!asked && !account.autoSync) return HardcoverPushOutcome.SwitchedOff
        var link = dao.link(bookUrl)
        if (link?.excluded == true) return HardcoverPushOutcome.SwitchedOff

        val progress = progressFor(bookUrl)
        val local = readingStatusFor(
            progress?.totalProgression,
            progress?.override ?: com.chmouel.liseur.domain.FinishedOverride.NONE,
            progress?.partialCoverage ?: false,
        )

        if (link?.matched != true) {
            // Matched once and remembered, so the search below runs on
            // the first push for a book and never again.
            val book = bookFor(bookUrl) ?: return HardcoverPushOutcome.NeedsMatch
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
        val steps = HardcoverProgress.plan(shelf, local, progress?.totalProgression, link.editionPages)
        if (steps.isEmpty()) return HardcoverPushOutcome.UpToDate

        for (step in steps) {
            val sent: RemoteResult<*> = when (step) {
                HardcoverStep.AddAsReading ->
                    client.addToLibrary(token, bookId, editionId, HardcoverStatus.CURRENTLY_READING)
                        .also { added ->
                            if (added is RemoteResult.Ok) shelf = shelf.copy(userBookId = added.value)
                        }

                HardcoverStep.MarkReading ->
                    client.setStatus(token, shelf.userBookId!!, HardcoverStatus.CURRENTLY_READING)

                HardcoverStep.MarkRead ->
                    client.setStatus(token, shelf.userBookId!!, HardcoverStatus.READ)

                is HardcoverStep.LogPages -> {
                    // The read a status change just started exists only
                    // on the server; ask which one it is before logging.
                    val readId = shelf.openReadId ?: when (
                        val again = client.shelfState(token, account.userId, bookId)
                    ) {
                        is RemoteResult.Failed -> return HardcoverPushOutcome.Failed(again.reason)
                        is RemoteResult.Ok -> again.value.openReadId
                    }
                    // No read to log on: the status went through, and the
                    // pages follow on the next push.
                    if (readId == null) RemoteResult.Ok(Unit)
                    else client.setProgress(token, readId, step.pages)
                }
            }
            if (sent is RemoteResult.Failed) return HardcoverPushOutcome.Failed(sent.reason)
        }
        dao.setPushedAt(bookUrl, now())
        return HardcoverPushOutcome.Pushed
    }

    /** The ISBN the file itself carries, when its identifier is one. */
    private fun isbnOf(book: Book): Isbn? =
        Isbn.from(WorkIdentifiers.dcFrom(book.workId, book.title, book.author))

    private fun HardcoverBookLink.matchedWith(edition: HardcoverEdition) = copy(
        hardcoverBookId = edition.bookId,
        editionId = edition.id,
        editionTitle = edition.title.ifBlank { edition.bookTitle },
        editionPages = edition.pages,
    )
}
