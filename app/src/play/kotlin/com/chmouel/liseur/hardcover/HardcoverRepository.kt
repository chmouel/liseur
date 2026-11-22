package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.WorkIdentifiers
import java.time.Instant
import java.time.ZoneId
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

data class BookGeneration(
    val id: Long,
    val url: String,
    val workId: String?,
    val fileModifiedAt: Long?,
    val linkGeneration: Long,
) {
    constructor(book: Book) : this(book.id, book.url, book.workId, book.fileModifiedAt, book.linkGeneration)
}

/** The editions found for a book, with the account and book generation they were looked up for. */
data class HardcoverCandidates(
    val accountUserId: Long,
    val generation: BookGeneration?,
    val editions: List<HardcoverEdition>,
    /** What the successful lookup asked for: the ISBN, or the title and author searched. */
    val query: String = "",
)

/** What came of choosing an edition, and pushing when asked. */
sealed interface EditionChoice {
    data object Chosen : EditionChoice
    data class Pushed(val outcome: HardcoverPushOutcome) : EditionChoice
    data object NotConnected : EditionChoice
    data object OtherAccount : EditionChoice
    data object AlreadyGone : EditionChoice
    data object SwitchedOff : EditionChoice
    data class Failed(val reason: SyncFailure) : EditionChoice
}

/** What came of resolving a rating or review conflict in Book details. */
sealed interface HardcoverConflictResolution {
    data object Resolved : HardcoverConflictResolution

    /** Liseur's value moved while Hardcover was asked; the conflict stays for another look. */
    data object Stale : HardcoverConflictResolution
    data object NotConnected : HardcoverConflictResolution
    data object SwitchedOff : HardcoverConflictResolution
    data object NeedsMatch : HardcoverConflictResolution
    data class Failed(val reason: SyncFailure) : HardcoverConflictResolution
}

/**
 * The Hardcover account, which edition each book is, and the push that
 * reports reading and syncs ratings and reviews.
 *
 * Reading status and progress are one-way: nothing Hardcover holds moves
 * reading positions here, so this is no position peer and never takes
 * part in a merge. Ratings and reviews sync both ways against stored
 * per-field bases ([HardcoverEvaluationPlan]).
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
    /**
     * Writes a value pulled from Hardcover into Liseur when the local value
     * is still `expected` and the book is still `generation`; false otherwise.
     */
    private val writeLocalEvaluation: suspend (
        bookUrl: String,
        field: String,
        expected: String?,
        value: String?,
        generation: BookGeneration,
    ) -> Boolean = { _, _, _, _, _ -> false },
    private val client: HardcoverClient = HardcoverClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> String = {
        Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    },
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val seal: (String) -> String = HardcoverAccount::seal,
    private val open: (HardcoverAccount) -> String? = { it.token },
) {
    private val lock = Mutex()

    val account: Flow<HardcoverAccount?> = dao.observeAccount()

    fun link(bookUrl: String): Flow<HardcoverBookLink?> = dao.observeLink(bookUrl)

    fun evaluations(bookUrl: String): Flow<List<HardcoverEvaluation>> = dao.observeEvaluations(bookUrl)

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
                dao.deleteEvaluations()
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
            if (account == null || dao.linksOwner() != account.userId) {
                dao.deleteLinks()
                dao.deleteEvaluations()
            }
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
                    dao.forgetEvaluations(batch)
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
    suspend fun candidates(bookUrl: String): RemoteResult<HardcoverCandidates> {
        val account = dao.account() ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val token = open(account) ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val book = bookFor(bookUrl)
            ?: return RemoteResult.Ok(HardcoverCandidates(account.userId, null, emptyList()))
        val generation = BookGeneration(book)
        when (val byIsbn = editionsByIsbn(token, book)) {
            is RemoteResult.Failed -> return byIsbn
            is RemoteResult.Ok -> if (byIsbn.value.isNotEmpty()) {
                val isbn = bookIsbn(book)?.isbn13.orEmpty()
                return RemoteResult.Ok(HardcoverCandidates(account.userId, generation, byIsbn.value, isbn))
            }
        }
        val query = searchQuery(book)
        val found = when (val searched = search(token, book)) {
            is RemoteResult.Failed -> return searched
            is RemoteResult.Ok ->
                HardcoverMatch.confident(book.matchTitle, book.matchAuthor, searched.value)?.let(::listOf)
                    ?: searched.value.take(PICKER_HITS)
        }
        if (found.isEmpty()) return RemoteResult.Ok(HardcoverCandidates(account.userId, generation, emptyList(), query))
        return when (val editions = client.editionsOfBooks(token, found.map { it.id })) {
            is RemoteResult.Failed -> editions
            is RemoteResult.Ok ->
                RemoteResult.Ok(HardcoverCandidates(account.userId, generation, editions.value, query))
        }
    }

    /**
     * The editions for what the reader typed in the picker: a search, an
     * ISBN, a Hardcover book id or the slug of a pasted book link. Needs
     * the account's token and nothing about the book; blank, one-character
     * and non-Hardcover input makes no request.
     */
    suspend fun lookup(input: String): RemoteResult<List<HardcoverEdition>> {
        val ref = HardcoverRef.parse(input)
        when {
            ref is HardcoverRef.Text && ref.text.length < MIN_SEARCH -> return RemoteResult.Ok(emptyList())
            ref is HardcoverRef.Blank || ref is HardcoverRef.NotHardcover || ref is HardcoverRef.BadId ->
                return RemoteResult.Ok(emptyList())
        }
        val account = dao.account() ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val token = open(account) ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val ids = when (ref) {
            is HardcoverRef.Isbn -> return client.editionsByIsbn(token, ref.isbn)
            is HardcoverRef.Text -> when (val hits = client.searchBooks(token, ref.text)) {
                is RemoteResult.Failed -> return hits
                is RemoteResult.Ok -> hits.value.take(PICKER_HITS).map { it.id }
            }
            else -> when (val books = client.booksByRef(token, ref)) {
                is RemoteResult.Failed -> return books
                is RemoteResult.Ok -> books.value
            }
        }
        if (ids.isEmpty()) return RemoteResult.Ok(emptyList())
        return client.editionsOfBooks(token, ids)
    }

    /** Public catalogue details for the edition matched to [bookUrl], or null when none is matched. */
    suspend fun editionDetails(bookUrl: String): RemoteResult<HardcoverEditionDetails?> {
        val account = dao.account() ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val token = open(account) ?: return RemoteResult.Failed(SyncFailure.Unauthorised)
        val link = dao.link(bookUrl)
        if (link?.matched != true) return RemoteResult.Ok(null)
        val editionId = link.editionId ?: return RemoteResult.Ok(null)
        return client.editionDetails(token, editionId)
    }

    /** The book's match as stored now, without watching it. */
    suspend fun currentLink(bookUrl: String): HardcoverBookLink? = dao.link(bookUrl)

    /** Notes that [url] became the book's cover override with digest [sha256]. */
    suspend fun rememberImportedCover(bookUrl: String, url: String, sha256: String) = lock.withLock {
        dao.setImportedCover(bookUrl, url, sha256)
    }

    /**
     * Remembers which edition a book is, if the account and book generation
     * still match [candidates], and pushes when [push] is true.
     */
    suspend fun chooseThenPush(
        candidates: HardcoverCandidates,
        bookUrl: String,
        edition: HardcoverEdition,
        push: Boolean,
        asked: Boolean,
    ): EditionChoice = lock.withLock {
        val account = dao.account() ?: return@withLock EditionChoice.NotConnected
        if (account.userId != candidates.accountUserId) return@withLock EditionChoice.OtherAccount
        val expected = candidates.generation ?: return@withLock EditionChoice.AlreadyGone
        val current = bookFor(bookUrl) ?: return@withLock EditionChoice.AlreadyGone
        if (!current.sameGeneration(expected) || dao.isForgetting(bookUrl)) {
            return@withLock EditionChoice.AlreadyGone
        }
        val link = chooseLocked(bookUrl, edition)
        if (!push) return@withLock EditionChoice.Chosen
        if (link.excluded) return@withLock EditionChoice.SwitchedOff
        if (!asked && !account.autoSync) return@withLock EditionChoice.Chosen
        if (!asked && account.tokenRefused) return@withLock EditionChoice.Failed(SyncFailure.Unauthorised)
        EditionChoice.Pushed(pushNoting(bookUrl, asked))
    }

    private suspend fun chooseLocked(bookUrl: String, edition: HardcoverEdition): HardcoverBookLink {
        val link = dao.link(bookUrl) ?: HardcoverBookLink(bookUrl = bookUrl)
        // The same Hardcover book keeps the rating this device set there and a finish still owed.
        val sameBook = link.hardcoverBookId == edition.bookId
        if (!sameBook) dao.forgetEvaluations(listOf(bookUrl))
        val updated = link.matchedWith(edition).copy(
            pushedAt = null,
            pushedRating = link.pushedRating.takeIf { sameBook },
            datesPending = link.datesPending && sameBook,
            editionChosen = true,
        )
        dao.upsertLink(updated)
        return updated
    }

    /** When each book was last checked because its details opened. */
    private val detailsChecks = mutableMapOf<String, Long>()

    /**
     * Checks a book when Book details opens, at most once every ten
     * minutes however often it opens; null when it was checked recently.
     */
    suspend fun checkFromDetails(bookUrl: String): HardcoverPushOutcome? = lock.withLock {
        val at = now()
        val last = listOfNotNull(dao.link(bookUrl)?.pushedAt, detailsChecks[bookUrl]).maxOrNull()
        if (last != null && at - last < DETAILS_CHECK_MILLIS) return@withLock null
        detailsChecks[bookUrl] = at
        pushNoting(bookUrl, asked = false)
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
        val generation = bookFor(bookUrl)?.let(::BookGeneration) ?: return HardcoverPushOutcome.UpToDate
        // A removal marks the book before it commits, so a mark now means the read may hold its replacement.
        if (dao.isForgetting(bookUrl)) return HardcoverPushOutcome.UpToDate

        if (link?.matched != true) {
            // Nothing to say about a book never opened, rated, reviewed or chosen; no search for it either.
            if (HardcoverPlan.targetStatus(local) == null && local.rating == null && local.review == null) {
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

        val storedRatingEval = dao.evaluation(bookUrl, PersonalEvaluation.RATING)
        val storedReviewEval = dao.evaluation(bookUrl, PersonalEvaluation.REVIEW)
        val onShelf = shelf.userBookId != null

        val ratingDecision = evaluateRating(onShelf, local, shelf, storedRatingEval, link.pushedRating)
        val reviewDecision = evaluateReview(onShelf, local, shelf, storedReviewEval)

        val entry = HardcoverPlan.entryStep(
            remote = shelf,
            local = local,
            chosenEdition = editionId.takeIf { link.editionChosen },
            ratingDecision = ratingDecision,
            reviewDecision = reviewDecision,
        )
        val status = HardcoverPlan.statusAfter(shelf, entry)
        val statusChanged = status != shelf.statusId
        val wroteRating = when (entry) {
            is HardcoverEntryStep.Add -> entry.rating != null
            is HardcoverEntryStep.Update -> entry.rating != null || entry.clearRating
            null -> false
        }
        val wroteReview = when (entry) {
            is HardcoverEntryStep.Add -> entry.review != null
            is HardcoverEntryStep.Update -> entry.review != null || entry.clearReview
            null -> false
        }
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
            is HardcoverEntryStep.Add -> client.addToLibrary(
                token = token,
                bookId = bookId,
                editionId = editionId,
                statusId = entry.statusId,
                rating = entry.rating,
                review = entry.review,
                reviewedAt = today().takeIf { entry.review != null },
            )
            is HardcoverEntryStep.Update -> client.updateEntry(
                token = token,
                userBookId = checkNotNull(shelf.userBookId),
                step = entry,
                reviewedAt = today().takeIf { entry.review != null },
            )
            null -> null
        }
        if (sent is RemoteResult.Failed) return HardcoverPushOutcome.Failed(sent.reason)
        var wrote = entry != null
        // Kept now, so a read update failing below cannot lose which rating this device set.
        if (wrote && ratingNow != link.pushedRating) dao.setPushedRating(bookUrl, ratingNow)
        val sentRating = local.ratingRaw?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.RATING, it) }
        val sentReview = HardcoverEvaluationPlan.normalizeReview(local.review)
        // Recorded before the read-back, so a failed read-back still knows what this device wrote.
        if (wroteRating) saveBase(bookUrl, PersonalEvaluation.RATING, sentRatingBase(sentRating))
        if (wroteReview) saveBase(bookUrl, PersonalEvaluation.REVIEW, HardcoverEvaluationBase(sentReview, sentReview))
        if (statusChanged || entry is HardcoverEntryStep.Add || wroteRating || wroteReview) {
            // Re-read after status or evaluation writes so the server's read row and normalized text become the base.
            shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                is RemoteResult.Failed -> return HardcoverPushOutcome.Failed(again.reason)
                is RemoteResult.Ok -> again.value
            }
        }
        if (wroteRating) {
            saveBase(
                bookUrl = bookUrl,
                field = PersonalEvaluation.RATING,
                base = HardcoverEvaluationBase(sentRating, HardcoverEvaluationPlan.encodeRemoteRating(shelf.rating)),
            )
        } else {
            applyPostEvaluationDecision(
                bookUrl = bookUrl,
                field = PersonalEvaluation.RATING,
                expectedLocal = local.ratingRaw,
                decision = ratingDecision,
                previous = storedRatingEval,
                pushedRating = link.pushedRating,
                generation = generation,
            )
        }
        if (wroteReview) {
            saveBase(
                bookUrl = bookUrl,
                field = PersonalEvaluation.REVIEW,
                base = HardcoverEvaluationBase(sentReview, HardcoverEvaluationPlan.normalizeReview(shelf.review)),
            )
        } else {
            applyPostEvaluationDecision(
                bookUrl = bookUrl,
                field = PersonalEvaluation.REVIEW,
                expectedLocal = local.review,
                decision = reviewDecision,
                previous = storedReviewEval,
                pushedRating = null,
                generation = generation,
            )
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
     * Resolves a rating or review conflict by keeping Liseur's current value
     * and writing it to Hardcover, then recording the new agreed base.
     */
    suspend fun resolveConflictKeepLocal(bookUrl: String, field: String): HardcoverConflictResolution = lock.withLock {
        val outcome = resolveConflictLocked(bookUrl, field, keepLocal = true)
        val reason = (outcome as? HardcoverConflictResolution.Failed)?.reason
        if (reason == SyncFailure.Unauthorised || reason == SyncFailure.Forbidden) dao.setTokenRefused(true)
        outcome
    }

    /**
     * Resolves a rating or review conflict by pulling Hardcover's current value
     * into Liseur via CAS and recording the new agreed base.
     */
    suspend fun resolveConflictUseRemote(bookUrl: String, field: String): HardcoverConflictResolution = lock.withLock {
        val outcome = resolveConflictLocked(bookUrl, field, keepLocal = false)
        val reason = (outcome as? HardcoverConflictResolution.Failed)?.reason
        if (reason == SyncFailure.Unauthorised || reason == SyncFailure.Forbidden) dao.setTokenRefused(true)
        outcome
    }

    private suspend fun resolveConflictLocked(
        bookUrl: String,
        field: String,
        keepLocal: Boolean,
    ): HardcoverConflictResolution {
        val account = dao.account() ?: return HardcoverConflictResolution.NotConnected
        val token = open(account) ?: return HardcoverConflictResolution.NotConnected
        if (dao.isForgetting(bookUrl)) return HardcoverConflictResolution.Resolved
        val link = dao.link(bookUrl)
        if (link?.excluded == true) return HardcoverConflictResolution.SwitchedOff
        if (link?.matched != true) return HardcoverConflictResolution.NeedsMatch
        val bookId = link.hardcoverBookId ?: return HardcoverConflictResolution.NeedsMatch
        val editionId = link.editionId ?: return HardcoverConflictResolution.NeedsMatch
        val local = localFor(bookUrl) ?: return HardcoverConflictResolution.Resolved
        val generation = bookFor(bookUrl)?.let(::BookGeneration) ?: return HardcoverConflictResolution.Resolved
        if (dao.isForgetting(bookUrl)) return HardcoverConflictResolution.Resolved

        var shelf = when (val asking = client.shelfState(token, account.userId, bookId)) {
            is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(asking.reason)
            is RemoteResult.Ok -> asking.value
        }
        if (account.tokenRefused) dao.setTokenRefused(false)

        if (keepLocal) {
            when (field) {
                PersonalEvaluation.RATING -> {
                    val localRating = local.ratingRaw?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.RATING, it) }
                    val remoteDouble = HardcoverPlan.ratingFromLocal(localRating)
                    if (shelf.userBookId != null) {
                        val update = HardcoverEntryStep.Update(
                            statusId = null,
                            rating = remoteDouble,
                            clearRating = remoteDouble == null && shelf.rating != null,
                        )
                        if (update.rating != null || update.clearRating) {
                            when (val sent = client.updateEntry(token, shelf.userBookId, update)) {
                                is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(sent.reason)
                                is RemoteResult.Ok -> Unit
                            }
                            dao.setPushedRating(bookUrl, remoteDouble)
                            saveBase(bookUrl, field, sentRatingBase(localRating))
                            shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                                is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(again.reason)
                                is RemoteResult.Ok -> again.value
                            }
                        }
                        saveBase(
                            bookUrl,
                            field,
                            HardcoverEvaluationBase(
                                local = localRating,
                                remote = HardcoverEvaluationPlan.encodeRemoteRating(shelf.rating),
                            ),
                        )
                    } else {
                        val target = HardcoverPlan.targetStatus(local) ?: HardcoverStatus.WANT_TO_READ
                        when (val sent = client.addToLibrary(token, bookId, editionId, target, remoteDouble)) {
                            is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(sent.reason)
                            is RemoteResult.Ok -> Unit
                        }
                        dao.setPushedRating(bookUrl, remoteDouble)
                        saveBase(bookUrl, field, sentRatingBase(localRating))
                        shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                            is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(again.reason)
                            is RemoteResult.Ok -> again.value
                        }
                        saveBase(
                            bookUrl,
                            field,
                            HardcoverEvaluationBase(
                                local = localRating,
                                remote = HardcoverEvaluationPlan.encodeRemoteRating(shelf.rating),
                            ),
                        )
                    }
                }
                PersonalEvaluation.REVIEW -> {
                    val localReview = HardcoverEvaluationPlan.normalizeReview(local.review)
                        ?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.REVIEW, it) }
                    if (shelf.userBookId != null) {
                        val update = HardcoverEntryStep.Update(
                            statusId = null,
                            rating = null,
                            review = localReview,
                            clearReview = localReview == null && shelf.review != null,
                        )
                        if (update.review != null || update.clearReview) {
                            val sent = client.updateEntry(
                                token = token,
                                userBookId = shelf.userBookId,
                                step = update,
                                reviewedAt = today().takeIf { update.review != null },
                            )
                            if (sent is RemoteResult.Failed) return HardcoverConflictResolution.Failed(sent.reason)
                            saveBase(bookUrl, field, HardcoverEvaluationBase(localReview, localReview))
                            shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                                is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(again.reason)
                                is RemoteResult.Ok -> again.value
                            }
                        }
                        saveBase(
                            bookUrl,
                            field,
                            HardcoverEvaluationBase(
                                local = localReview,
                                remote = HardcoverEvaluationPlan.normalizeReview(shelf.review),
                            ),
                        )
                    } else {
                        val target = HardcoverPlan.targetStatus(local) ?: HardcoverStatus.WANT_TO_READ
                        val sent = client.addToLibrary(
                            token = token,
                            bookId = bookId,
                            editionId = editionId,
                            statusId = target,
                            rating = null,
                            review = localReview,
                            reviewedAt = today().takeIf { localReview != null },
                        )
                        if (sent is RemoteResult.Failed) return HardcoverConflictResolution.Failed(sent.reason)
                        saveBase(bookUrl, field, HardcoverEvaluationBase(localReview, localReview))
                        shelf = when (val again = client.shelfState(token, account.userId, bookId)) {
                            is RemoteResult.Failed -> return HardcoverConflictResolution.Failed(again.reason)
                            is RemoteResult.Ok -> again.value
                        }
                        saveBase(
                            bookUrl,
                            field,
                            HardcoverEvaluationBase(
                                local = localReview,
                                remote = HardcoverEvaluationPlan.normalizeReview(shelf.review),
                            ),
                        )
                    }
                }
            }
        } else {
            when (field) {
                PersonalEvaluation.RATING -> {
                    val remoteEncoded = HardcoverEvaluationPlan.encodeRemoteRating(shelf.rating)
                    val pulledLocal = HardcoverEvaluationPlan.ratingToLocal(shelf.rating)
                    val base = HardcoverEvaluationBase(local = pulledLocal, remote = remoteEncoded)
                    if (pullLocal(bookUrl, field, local.ratingRaw, pulledLocal, generation)) {
                        saveBase(bookUrl, field, base)
                    } else {
                        return staleConflict(bookUrl, field)
                    }
                }
                PersonalEvaluation.REVIEW -> {
                    val remoteReview = HardcoverEvaluationPlan.normalizeReview(shelf.review)
                    if (remoteReview != null && !PersonalEvaluation.valid(PersonalEvaluation.REVIEW, remoteReview)) {
                        dao.upsertEvaluation(
                            HardcoverEvaluation(
                                bookUrl = bookUrl,
                                field = field,
                                baseLocal = dao.evaluation(bookUrl, field)?.baseLocal,
                                baseRemote = dao.evaluation(bookUrl, field)?.baseRemote,
                                hasBase = dao.evaluation(bookUrl, field)?.hasBase == true,
                                conflict = true,
                                conflictLocal = HardcoverEvaluationPlan.normalizeReview(local.review),
                                conflictRemote = remoteReview,
                                remoteTooLong = true,
                            ),
                        )
                        return HardcoverConflictResolution.Resolved
                    }
                    val base = HardcoverEvaluationBase(local = remoteReview, remote = remoteReview)
                    if (pullLocal(bookUrl, field, local.review, remoteReview, generation)) {
                        saveBase(bookUrl, field, base)
                    } else {
                        return staleConflict(bookUrl, field)
                    }
                }
            }
        }
        return HardcoverConflictResolution.Resolved
    }

    private fun evaluateRating(
        onShelf: Boolean,
        local: LocalReading,
        shelf: HardcoverShelfState,
        stored: HardcoverEvaluation?,
        pushedRating: Double?,
    ): HardcoverEvaluationDecision {
        if (stored?.conflict == true) {
            val localNorm = local.ratingRaw?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.RATING, it) }
            val remoteNorm = HardcoverEvaluationPlan.normalizeRemoteRating(shelf.rating)
            val remoteEncoded = HardcoverEvaluationPlan.encodeRemoteRating(remoteNorm)
            return if (onShelf && HardcoverEvaluationPlan.ratingsEquivalent(localNorm, remoteNorm)) {
                HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase(localNorm, remoteEncoded))
            } else {
                HardcoverEvaluationDecision.Conflict(
                    conflictLocal = localNorm,
                    conflictRemote = remoteEncoded,
                )
            }
        }
        return HardcoverEvaluationPlan.planRating(
            onShelf = onShelf,
            localRaw = local.ratingRaw,
            remoteRating = shelf.rating,
            storedBase = stored?.base,
            pushedRating = pushedRating,
        )
    }

    private fun evaluateReview(
        onShelf: Boolean,
        local: LocalReading,
        shelf: HardcoverShelfState,
        stored: HardcoverEvaluation?,
    ): HardcoverEvaluationDecision {
        if (stored?.conflict == true) {
            val localNorm = HardcoverEvaluationPlan.normalizeReview(local.review)
                ?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.REVIEW, it) }
            val remoteNorm = HardcoverEvaluationPlan.normalizeReview(shelf.review)
            return if (onShelf && HardcoverEvaluationPlan.reviewsEquivalent(localNorm, remoteNorm)) {
                HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase(localNorm, remoteNorm))
            } else {
                val remoteValid = remoteNorm == null || PersonalEvaluation.valid(PersonalEvaluation.REVIEW, remoteNorm)
                HardcoverEvaluationDecision.Conflict(
                    conflictLocal = localNorm,
                    conflictRemote = remoteNorm,
                    remoteTooLong = !remoteValid,
                )
            }
        }
        return HardcoverEvaluationPlan.planReview(
            onShelf = onShelf,
            localRaw = local.review,
            remoteRaw = shelf.review,
            storedBase = stored?.base,
        )
    }

    private suspend fun applyPostEvaluationDecision(
        bookUrl: String,
        field: String,
        expectedLocal: String?,
        decision: HardcoverEvaluationDecision,
        previous: HardcoverEvaluation?,
        pushedRating: Double?,
        generation: BookGeneration,
    ) {
        when (decision) {
            is HardcoverEvaluationDecision.NoChange -> {
                if (decision.recordBase != null) {
                    saveBase(bookUrl, field, decision.recordBase)
                } else if (previous == null && field == PersonalEvaluation.RATING) {
                    HardcoverEvaluationPlan.migrationRatingBase(null, pushedRating)?.let { migrated ->
                        saveBase(bookUrl, field, migrated)
                    }
                }
            }
            is HardcoverEvaluationDecision.PushToRemote -> Unit
            is HardcoverEvaluationDecision.PullToLocal -> {
                if (pullLocal(bookUrl, field, expectedLocal, decision.value, generation)) {
                    saveBase(bookUrl, field, decision.base)
                }
            }
            is HardcoverEvaluationDecision.Conflict -> {
                val keptBase = previous?.base ?: if (field == PersonalEvaluation.RATING) {
                    HardcoverEvaluationPlan.migrationRatingBase(null, pushedRating)
                } else {
                    null
                }
                dao.upsertEvaluation(
                    HardcoverEvaluation(
                        bookUrl = bookUrl,
                        field = field,
                        baseLocal = keptBase?.local,
                        baseRemote = keptBase?.remote,
                        hasBase = keptBase != null,
                        conflict = true,
                        conflictLocal = decision.conflictLocal,
                        conflictRemote = decision.conflictRemote,
                        remoteTooLong = decision.remoteTooLong,
                    ),
                )
            }
        }
    }

    /** Shows the conflict again with Liseur's value as it is now. */
    private suspend fun staleConflict(bookUrl: String, field: String): HardcoverConflictResolution {
        val now = localFor(bookUrl)
        val current = if (field == PersonalEvaluation.RATING) now?.ratingRaw else HardcoverEvaluationPlan.normalizeReview(now?.review)
        dao.evaluation(bookUrl, field)?.let { dao.upsertEvaluation(it.copy(conflictLocal = current)) }
        return HardcoverConflictResolution.Stale
    }

    private fun sentRatingBase(local: String?) = HardcoverEvaluationBase(
        local,
        HardcoverEvaluationPlan.encodeRemoteRating(HardcoverEvaluationPlan.ratingFromLocal(local)),
    )

    /** [writeLocalEvaluation], refused for a book whose removal or replacement is under way. */
    private suspend fun pullLocal(
        bookUrl: String,
        field: String,
        expected: String?,
        value: String?,
        generation: BookGeneration,
    ): Boolean = !dao.isForgetting(bookUrl) && writeLocalEvaluation(bookUrl, field, expected, value, generation)

    private suspend fun saveBase(bookUrl: String, field: String, base: HardcoverEvaluationBase) {
        dao.upsertEvaluation(
            HardcoverEvaluation(
                bookUrl = bookUrl,
                field = field,
                baseLocal = base.local,
                baseRemote = base.remote,
                hasBase = true,
                conflict = false,
                conflictLocal = null,
                conflictRemote = null,
                remoteTooLong = false,
            ),
        )
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

    private fun searchQuery(book: Book): String =
        listOfNotNull(book.matchTitle, book.matchAuthor).joinToString(" ").trim()

    private suspend fun search(token: String, book: Book): RemoteResult<List<HardcoverBook>> {
        val query = searchQuery(book)
        if (query.isEmpty()) return RemoteResult.Ok(emptyList())
        return client.searchBooks(token, query)
    }

    /** The editions carrying the file's ISBN, or none when its identifier is no ISBN. */
    private suspend fun editionsByIsbn(token: String, book: Book): RemoteResult<List<HardcoverEdition>> {
        val isbn = bookIsbn(book) ?: return RemoteResult.Ok(emptyList())
        return client.editionsByIsbn(token, isbn)
    }

    private fun bookIsbn(book: Book): Isbn? =
        Isbn.from(WorkIdentifiers.dcFrom(book.workId, book.stableTitle, book.identityAuthor ?: book.stableAuthor))

    private fun HardcoverBookLink.matchedWith(edition: HardcoverEdition) = copy(
        hardcoverBookId = edition.bookId,
        editionId = edition.id,
        editionTitle = edition.title.ifBlank { edition.bookTitle },
        editionPages = edition.pages,
    )

    private companion object {
        /** Search hits whose editions the picker offers when none is a sure match. */
        const val PICKER_HITS = 5
        const val MIN_SEARCH = 2
        private const val SQL_BATCH = 500
        private const val DETAILS_CHECK_MILLIS = 10 * 60 * 1_000L
    }
}

// The file's or catalogue's own title and author, as work identity uses
// them: a renamed book must not be matched to whatever its new name finds.
private val Book.matchTitle: String? get() = stableTitle ?: title
private val Book.matchAuthor: String? get() = stableAuthor ?: author

private fun Book.sameGeneration(other: BookGeneration): Boolean =
    id == other.id && url == other.url && workId == other.workId &&
        fileModifiedAt == other.fileModifiedAt && linkGeneration == other.linkGeneration
