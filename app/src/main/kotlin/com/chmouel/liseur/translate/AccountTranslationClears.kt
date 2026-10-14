package com.chmouel.liseur.translate

import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.remote.ServerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which clears of the account's translations a reader's request comes
 * to (ADR-0050): the connected liseur-sync account's, under the work
 * name it knows a book by. Taken when the reader asks, because the
 * name goes when the book does.
 */
class AccountTranslationClears(
    private val serverDao: RemoteServerDao,
    private val identityDao: WorkIdentityDao,
) {
    /** The connected liseur-sync account's key, or null with none connected. */
    suspend fun peer(): String? =
        serverDao.get()
            ?.takeIf { it.kind == ServerKind.LISEUR_SYNC && it.credentials != null }
            ?.accountKey

    /**
     * The book's clear, when the account has a name for it this device
     * trusts. A guessed name could be another book's, and clearing
     * that one is worse than leaving this one.
     */
    suspend fun forBook(bookUrl: String): List<TranslationAccountClear> {
        val peer = peer() ?: return emptyList()
        val alias = identityDao.alias(bookUrl, peer)?.takeIf { it.usable } ?: return emptyList()
        return listOf(TranslationAccountClear(peer, alias.workId))
    }

    /** Whether a liseur-sync account is connected, so the account's copy can be cleared at all. */
    fun observeConnected(): Flow<Boolean> =
        serverDao.observe()
            .map { it?.kind == ServerKind.LISEUR_SYNC && it.credentials != null }
            .distinctUntilChanged()

    /** The books [forBook] has a clear for, as they change: where the offer to clear is drawn. */
    fun observeBooks(): Flow<Set<String>> =
        combine(serverDao.observe(), identityDao.observeAliases()) { server, aliases ->
            val peer = server
                ?.takeIf { it.kind == ServerKind.LISEUR_SYNC && it.credentials != null }
                ?.accountKey
                ?: return@combine emptySet()
            aliases.filter { it.peerId == peer && it.usable }.mapTo(mutableSetOf()) { it.bookUrl }
        }.distinctUntilChanged()

    /** The account-wide clear, with an account connected. */
    suspend fun everyWork(): List<TranslationAccountClear> =
        listOfNotNull(peer()?.let(TranslationAccountClear::everyWork))
}
