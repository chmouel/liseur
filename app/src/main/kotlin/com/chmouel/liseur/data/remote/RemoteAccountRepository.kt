package com.chmouel.liseur.data.remote

import android.util.Log
import com.chmouel.liseur.data.bookorbit.BookOrbitScope
import com.chmouel.liseur.data.bookorbit.BookOrbitUrl
import com.chmouel.liseur.data.calibre.CalibreParsing
import com.chmouel.liseur.data.calibre.CalibreSetupClient
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationSyncDao
import com.chmouel.liseur.data.db.UploadRefusalDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.SeriesExtraDao
import com.chmouel.liseur.data.db.SessionRefusalDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.komga.KomgaSetupClient
import com.chmouel.liseur.data.kosync.KosyncPairing
import com.chmouel.liseur.data.kosync.KosyncProbe
import com.chmouel.liseur.data.kosync.ProvedKosyncPairing
import com.chmouel.liseur.data.opds.OpdsSetupClient
import com.chmouel.liseur.data.opds.StarterCatalog
import com.chmouel.liseur.data.liseursync.LiseurSyncServerSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores the one connected server and hands out its credentials.
 *
 * The kinds of server differ in how they are signed into and in what
 * they can be asked for, but not in what an account *is*, so this holds
 * whichever one is connected and leaves the differences to
 * [ServerSetup] and to [RemoteServer.credentials].
 */
class RemoteAccountRepository(
    private val dao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val bookRemoval: BookRemoval,
    private val seriesExtraDao: SeriesExtraDao,
    /**
     * liseur-sync keeps its per-book agreements in tables of its own,
     * keyed by the account; leaving or switching away from one forgets
     * them, exactly as it always has. Null in tests that do not exercise
     * a liseur-sync account.
     */
    private val peerStateDao: SyncPeerStateDao? = null,
    private val identityDao: WorkIdentityDao? = null,
    /**
     * Sittings remember whether they reached a server, and that is only
     * ever true of the server they were sent to. Null in tests that do
     * not exercise a liseur-sync account.
     */
    private val sessionDao: ReadingSessionDao? = null,
    /**
     * What a server confirmed about the reader's highlights (ADR-0028).
     * A rev is that server's own number; leaving one behind for the next
     * account would have it refuse edits over a history it has no part
     * in. Null in tests that do not exercise a liseur-sync account.
     */
    private val annotationSyncDao: AnnotationSyncDao? = null,
    /**
     * What a server would not take. One server's opinion, and no use to
     * the next: the same book may be perfectly acceptable there, and a
     * reader who has just switched accounts should be offered it.
     */
    private val uploadRefusalDao: UploadRefusalDao? = null,
    /**
     * Sittings a server said it would never take (one server's verdict,
     * so it goes with the account). Null in tests that do not exercise a
     * liseur-sync account.
     */
    private val sessionRefusalDao: SessionRefusalDao? = null,
    private val sessionTransmissionDao: com.chmouel.liseur.data.db.SessionTransmissionDao? = null,
    /**
     * How far a starter shelf has been explored. The walk belongs to the
     * catalog the account pointed at, so leaving it throws the progress
     * away: a shelf reconnected later starts its discovery again rather
     * than inheriting an "already exhausted" verdict about books it no
     * longer has. Null in tests that do not exercise a starter shelf.
     */
    private val starterProgressDao: com.chmouel.liseur.data.db.StarterCatalogProgressDao? = null,
    /**
     * What each account agreed the syncable settings were. Peer state
     * like the rest: it moves with a reconnect and goes with a
     * disconnect. Null in tests that do not sync settings.
     */
    private val settingsSyncState: com.chmouel.liseur.data.settings.SettingsSyncRepository? = null,
    /**
     * The KOReader pairing, which a connection may drop, replace or
     * leave alone.
     *
     * Reached through `KosyncAccountRepository` rather than through the
     * tables, so a pairing is put down through one door however it
     * goes: clearing its agreements and its reported status is that
     * repository's job and should not be spelled out twice. A provider
     * rather than the repository itself, because the two are built at
     * opposite ends of the composition root.
     */
    private val kosync: () -> KosyncPairing = { KosyncPairing.None },
    /**
     * BookOrbit's session, so that publishing or dropping an account
     * takes effect on the tokens the network layer is already holding.
     * Null in tests that do not exercise a BookOrbit account.
     */
    private val bookOrbit: com.chmouel.liseur.data.bookorbit.BookOrbitSession? = null,
    /** File bindings outlive disconnect only while their downloaded book does. */
    private val bookOrbitBindingDao: com.chmouel.liseur.data.db.BookOrbitBindingDao? = null,
    private val bookOrbitCfiDao: com.chmouel.liseur.data.db.BookOrbitCfiDao? = null,
    private val bookOrbitLocalCfiDao: com.chmouel.liseur.data.db.BookOrbitLocalCfiDao? = null,
    private val bookOrbitPositionAgreementDao: com.chmouel.liseur.data.db.BookOrbitPositionAgreementDao? = null,
    private val bookOrbitPositionTraversalDao: com.chmouel.liseur.data.db.BookOrbitPositionTraversalDao? = null,
    private val bookOrbitStatusAgreementDao: com.chmouel.liseur.data.db.BookOrbitStatusAgreementDao? = null,
    private val setups: Map<ServerKind, ServerSetup> = mapOf(
        ServerKind.CALIBRE to CalibreSetupClient(),
        ServerKind.KOMGA to KomgaSetupClient(),
        ServerKind.LISEUR_SYNC to LiseurSyncServerSetup(),
        ServerKind.CUSTOM to OpdsSetupClient(),
    ),
    /**
     * Connecting and disconnecting each touch several tables, and a sync
     * or a catalog refresh may be running at the same time. Doing them in
     * one go is what lets those runs check whose account they are writing
     * for and be sure the answer is still true when they write.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    val server: Flow<RemoteServer?> = dao.observe()

    /**
     * What the account looks like to a caller that cannot suspend — the
     * image loader, mainly, which signs Coil's cover requests from an
     * OkHttp interceptor.
     */
    private sealed interface Snapshot {
        /** There is no connected account. */
        data object None : Snapshot

        data class Account(
            val origin: RemoteOrigin,
            val credentials: RemoteCredentials,
        ) : Snapshot
    }

    /**
     * The account as last read, or null when it has not been read yet.
     *
     * "Not read yet" and "no account" are kept apart on purpose: they
     * are the same absence to a caller but opposite instructions to this
     * class, and conflating them is how a cold cache turns into a
     * permanent one.
     *
     * This used to be filled in as a side effect of [credentials], which
     * meant covers only loaded if something else had happened to ask for
     * the account first. That held until the catalog and the download
     * worker stopped calling it, at which point every Komga cover
     * started coming back 401. A cache maintained by a caller that has
     * no idea it is maintaining one is not a cache, so this one keeps
     * itself.
     */
    @Volatile
    private var cached: Snapshot? = null

    /**
     * Bumped on the way into a mutation and again on the way out.
     *
     * A read that began before a change and finished after it holds a
     * row that is no longer true. Doing the first bump *before* the
     * mutation starts is what makes that detectable: the reader compares
     * the counter across its own query and throws away anything it
     * loaded across a change, so a disconnected account can never be
     * published back into the cache by a read that was already in
     * flight.
     */
    private val generation = AtomicLong()

    private val loading = Any()

    suspend fun current(): RemoteServer? = dao.get()

    suspend fun credentials(): RemoteCredentials? = dao.get()?.credentials

    /** Reads the account into the cache, so the first cover need not wait. */
    suspend fun prime() {
        val at = generation.get()
        val snapshot = snapshotOf(dao.get())
        if (generation.get() == at) cached = snapshot
    }

    /** How to sign a request to [url], or null when it is not our server. */
    fun credentialsForUrl(url: String): RemoteCredentials? {
        val account = (cached ?: load()) as? Snapshot.Account ?: return null
        return account.credentials.takeIf { account.origin.covers(url) }
    }

    /**
     * Fills a cold cache, blocking until it has an answer.
     *
     * There is nowhere to suspend to: this is called from an OkHttp
     * interceptor. It is one indexed single-row query, it happens at
     * most once between changes, and the lock makes a burst of cover
     * requests at startup share a single read rather than each doing
     * their own. [prime] normally gets there first.
     */
    private fun load(): Snapshot = synchronized(loading) {
        cached?.let { return it }
        repeat(LOAD_ATTEMPTS) {
            val at = generation.get()
            val snapshot = snapshotOf(runBlocking { dao.get() })
            if (generation.get() == at) {
                cached = snapshot
                return snapshot
            }
        }
        // Something is changing the account faster than it can be read.
        // Answering "no account" only costs a cover; answering with what
        // was read could cost the credentials.
        return Snapshot.None
    }

    private fun snapshotOf(server: RemoteServer?): Snapshot {
        val credentials = server?.credentials ?: return Snapshot.None
        // A path prefix is the right boundary for a server Liseur knows
        // the shape of, where everything it serves hangs off one root.
        // An arbitrary OPDS catalog does not work that way: the feed
        // lives at /opds and the covers at /get, so a prefix rule leaves
        // every cover on an authenticated catalog unsigned, and a shelf
        // of blank covers is what the reader sees. Origin is the rule
        // the catalog itself is fetched under, and ADR-0015 says why.
        val origin = if (server.kind.linksAreAbsolute) {
            RemoteOrigin.ofOrigin(server.baseUrl)
        } else {
            RemoteOrigin.of(server.baseUrl)
        } ?: return Snapshot.None
        return Snapshot.Account(origin, credentials)
    }

    /**
     * Runs a change to the account with the cache emptied around it.
     *
     * Nothing here writes the new value into the cache; it only clears
     * it, so the next reader goes and looks. That is deliberate — the
     * cache should hold what the database actually committed, not what
     * this call believed it was about to write.
     */
    private suspend fun <T> changingAccount(block: suspend () -> T): T {
        invalidate()
        return try {
            block()
        } finally {
            invalidate()
        }
    }

    private fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    /** Probes a calibre-web server and, if it answers, saves it as the account. */
    suspend fun connectCalibre(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
        /**
         * Run once the server has answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when the probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): SetupResult = connect(
        kind = ServerKind.CALIBRE,
        url = url,
        credentials = RemoteCredentials.Basic(username, password),
        allowHttp = allowHttp,
        beforePublish = beforePublish,
    )

    /** Probes a Komga server and, if it answers, saves it as the account. */
    suspend fun connectKomga(
        url: String,
        apiKey: String,
        allowHttp: Boolean = false,
        /**
         * Run once the server has answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when the probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): SetupResult = connect(
        kind = ServerKind.KOMGA,
        url = url,
        credentials = RemoteCredentials.ApiKey(apiKey),
        allowHttp = allowHttp,
        beforePublish = beforePublish,
    )

    /**
     * Signs into a liseur-sync server and keeps the minted device token.
     *
     * The password goes no further than the setup call: it buys an
     * hour-long credential that can do nothing but mint device tokens,
     * and once one exists neither is needed again. Nothing writes the
     * password down.
     */
    suspend fun connectLiseurSync(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
        /**
         * Run once the server has answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when the probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): SetupResult = connect(
        kind = ServerKind.LISEUR_SYNC,
        url = url,
        credentials = RemoteCredentials.Basic(username, password),
        allowHttp = allowHttp,
        beforePublish = beforePublish,
    )

    /**
     * Signs into a BookOrbit server and keeps the session it mints.
     *
     * The password goes no further than the probe. BookOrbit offers a
     * native client nothing narrower, so it is exchanged once for an
     * access token that lasts minutes and a refresh token that lasts a
     * week; neither the password nor anything derived from it is written
     * down in the clear.
     */
    suspend fun connectBookOrbit(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
        /**
         * Run once the server has answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when the probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): SetupResult = connect(
        kind = ServerKind.BOOKORBIT,
        url = url,
        credentials = RemoteCredentials.Basic(username, password),
        allowHttp = allowHttp,
        beforePublish = beforePublish,
    )

    /**
     * Connects to a liseur-sync server with a device token minted
     * elsewhere, once the server has said what it allows.
     */
    suspend fun connectLiseurSyncToken(
        url: String,
        token: String,
        allowHttp: Boolean = false,
        /**
         * Run once the server has answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when the probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): SetupResult = connect(
        kind = ServerKind.LISEUR_SYNC,
        url = url,
        credentials = RemoteCredentials.Bearer(token),
        allowHttp = allowHttp,
        beforePublish = beforePublish,
    )

    /**
     * Connects an open catalog, and only while nothing is connected.
     *
     * For the shelf of free books the empty library offers. That card
     * is shown only when there is no server, but a tap can outlive the
     * state that allowed it: probing the catalog takes a moment, and a
     * reader who connects their own server in that moment must not have
     * it replaced by one they tapped before it existed. So the account
     * is read again inside the transaction that would write over it,
     * and the connection made first is the one that stands.
     *
     * Anonymous, and no plain-text fallback. An open catalog has no
     * login, and the address is one Liseur ships rather than one
     * anybody typed, so there is nobody to ask about either.
     *
     * A KOReader pairing is left exactly where it is. It outlives a
     * catalog disconnection on purpose, so an empty library may well
     * have one standing, and it is the reader's own configuration:
     * taking it away because they tapped a card offering free books
     * would lose credentials and peer agreements they never mentioned.
     * Those books syncing to their sync server is what a sync server
     * is for.
     */
    suspend fun connectOpenCatalogIfDisconnected(
        catalogUrl: String,
        shelfLimit: Int? = null,
    ): OpenCatalogOutcome {
        if (dao.get() != null) return OpenCatalogOutcome.ALREADY_CONNECTED
        val setup = setups[ServerKind.CUSTOM] ?: return OpenCatalogOutcome.UNREACHABLE
        val probed = setup.connect(catalogUrl, RemoteCredentials.Anonymous, allowHttp = false)
        val capabilities = when (probed) {
            is SetupResult.Failure -> return OpenCatalogOutcome.UNREACHABLE
            is SetupResult.Success -> probed.capabilities
        }
        var published = false
        changingAccount {
            inTransaction {
                if (dao.get() != null) return@inTransaction
                storeLocked(
                    ServerKind.CUSTOM,
                    RemoteCredentials.Anonymous,
                    capabilities,
                    keepsPairing = true,
                    shelfLimit = shelfLimit,
                )
                published = true
            }
        }
        return if (published) {
            OpenCatalogOutcome.CONNECTED
        } else {
            OpenCatalogOutcome.ALREADY_CONNECTED
        }
    }

    /**
     * Connects Project Gutenberg's starter catalog, preferring [languageCode]
     * and falling back to English if the requested language feed has no matching books.
     */
    suspend fun connectStarterCatalogIfDisconnected(
        category: StarterCatalog.Category,
        shelfLimit: Int? = null,
        languageCode: String? = null,
    ): StarterCatalogOutcome {
        if (dao.get() != null) return StarterCatalogOutcome.ALREADY_CONNECTED
        val setup = setups[ServerKind.CUSTOM] ?: return StarterCatalogOutcome.UNREACHABLE

        val normalizedLang = StarterCatalog.baseLanguage(languageCode)
            ?.takeIf { it in StarterCatalog.SUPPORTED_LANGUAGES }
        val targetUrl = category.url(normalizedLang)

        if (normalizedLang != null && targetUrl != category.englishUrl) {
            val probed = setup.connect(targetUrl, RemoteCredentials.Anonymous, allowHttp = false)
            when (probed) {
                is SetupResult.Success -> {
                    if (probed.capabilities.hasBooks) {
                        return publishStarter(probed.capabilities, shelfLimit, isFallback = false)
                    }
                    // The feed answered successfully but contained no books ("No records found")
                    // -> fall through to English
                }
                is SetupResult.Failure -> {
                    // Network or server failure: report unreachable rather than quiet English fallback
                    return StarterCatalogOutcome.UNREACHABLE
                }
            }
        }

        val englishProbed = setup.connect(category.englishUrl, RemoteCredentials.Anonymous, allowHttp = false)
        val capabilities = when (englishProbed) {
            is SetupResult.Failure -> return StarterCatalogOutcome.UNREACHABLE
            is SetupResult.Success -> englishProbed.capabilities
        }
        val isFallback = normalizedLang != null && targetUrl != category.englishUrl
        return publishStarter(capabilities, shelfLimit, isFallback = isFallback)
    }

    private suspend fun publishStarter(
        capabilities: ServerCapabilities,
        shelfLimit: Int?,
        isFallback: Boolean,
    ): StarterCatalogOutcome {
        var published = false
        changingAccount {
            inTransaction {
                if (dao.get() != null) return@inTransaction
                storeLocked(
                    ServerKind.CUSTOM,
                    RemoteCredentials.Anonymous,
                    capabilities,
                    keepsPairing = true,
                    shelfLimit = shelfLimit,
                )
                published = true
            }
        }
        return if (published) {
            if (isFallback) StarterCatalogOutcome.CONNECTED_FALLBACK else StarterCatalogOutcome.CONNECTED
        } else {
            StarterCatalogOutcome.ALREADY_CONNECTED
        }
    }

    /**
     * Connects a Custom server: an OPDS catalog, a KOReader sync
     * server, or one of the two.
     *
     * Both halves are asked before either is written down. A network
     * call cannot join a transaction, but publication can wait for it,
     * and waiting is what stops a reader being left connected to the
     * half that answered while the form that reported the other half's
     * error has already been cleared.
     *
     * The form is authoritative. Whatever Custom pairing was saved
     * before, a filled sync address replaces it and an empty one removes
     * it. Otherwise choosing
     * catalog-only Custom would quietly leave the last server's pairing
     * running against a field the reader deliberately left blank.
     */
    suspend fun connectCustom(
        catalogUrl: String,
        username: String,
        password: String,
        kosyncUrl: String,
        kosyncUsername: String,
        kosyncPassword: String,
        allowHttp: Boolean = false,
        speaksForKosync: Boolean = true,
        /**
         * Run once both halves have answered and before the account is
         * written, for work that has to stop before the account it runs
         * against is retired. Never run when a probe fails, so a
         * mistyped address costs nothing.
         */
        beforePublish: suspend () -> Unit = {},
    ): CustomSetupResult {
        val wantsCatalog = catalogUrl.isNotBlank()
        val wantsKosync = kosyncUrl.isNotBlank()
        // Nothing typed is not a connection. Refused here as well as in
        // the form, because "connected to no server at all" is a row
        // every catalog and sync path would then have to think about.
        if (!wantsCatalog && !wantsKosync) {
            return CustomSetupResult(catalog = SetupFailure.WrongServer)
        }
        val setup = setups[ServerKind.CUSTOM]
            ?: return CustomSetupResult(catalog = SetupFailure.WrongServer)

        // A connection with no catalog has nothing to authenticate
        // against, so the catalog fields are not part of it. Carried
        // through anyway, a name left in a field the reader then blanked
        // would end up in `remote_server.username` and in the account
        // key, making one sync server two accounts depending on what was
        // typed above it.
        val credentials = if (wantsCatalog) {
            customCredentials(username, password)
        } else {
            RemoteCredentials.Anonymous
        }
        val catalog = if (wantsCatalog) {
            when (val probed = setup.connect(catalogUrl, credentials, allowHttp)) {
                is SetupResult.Failure -> return CustomSetupResult(catalog = probed.reason)
                is SetupResult.Success -> probed.capabilities
            }
        } else {
            null
        }

        val pairing = if (wantsKosync) {
            when (val probe = kosync().verify(kosyncUrl, kosyncUsername, kosyncPassword)) {
                is KosyncProbe.Failure -> return CustomSetupResult(kosync = probe.reason)
                is KosyncProbe.Proved -> probe.pairing
            }
        } else {
            null
        }

        // The sync address is the connection's identity when it is the
        // only address there is, so it is taken from the proved pairing
        // rather than from the form. The reader may leave the scheme off
        // — the field's own placeholder invites it — and storing
        // `sync.example.com` where the pairing stored
        // `https://sync.example.com` gives one server two spellings, two
        // account keys, and a `baseUrl` nothing can parse.
        val capabilities = catalog
            ?: pairing?.let { kosyncOnlyCapabilities(it.baseUrl, kosyncUsername) }
            ?: return CustomSetupResult(catalog = SetupFailure.WrongServer)

        // With no catalog there is no catalog login, but there is still
        // a person: the kosync one. Left out, `remote_server.username`
        // is null, two kosync users on one sync server share an
        // `accountKey`, a switch between them reads as the same account
        // coming back, and Settings cannot say who is connected.
        val syncOnlyUser = if (catalog == null) {
            kosyncUsername.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        beforePublish()
        publishCustom(capabilities, credentials, pairing, syncOnlyUser, speaksForKosync)
        return CustomSetupResult()
    }

    /**
     * A blank username and password mean the catalog is open to
     * everyone, which is the common case for OPDS and has to be
     * sayable: a null credential already means "the stored secret
     * cannot be read", and an open catalog spelled that way would be
     * reported as a broken account for ever.
     */
    private fun customCredentials(username: String, password: String): RemoteCredentials =
        if (username.isBlank() && password.isBlank()) {
            RemoteCredentials.Anonymous
        } else {
            RemoteCredentials.Basic(username.trim(), password)
        }

    /**
     * What a Custom connection with only a sync address amounts to.
     *
     * Its base URL is the sync server, because that is the only address
     * there is and a connection has to be shown somewhere. Its catalog
     * URL is null, which is the whole point: every catalog path reads
     * that and finds nothing to do, rather than trying the base URL and
     * parsing a kosync endpoint as a feed.
     */
    private fun kosyncOnlyCapabilities(kosyncUrl: String, kosyncUsername: String) =
        ServerCapabilities(
            baseUrl = kosyncUrl.trim().trimEnd('/'),
            canDownload = false,
            accountId = null,
            displayName = kosyncUsername.trim(),
            catalogUrl = null,
        )

    /**
     * Writes the server and the pairing together, or writes neither.
     *
     * Waiting for both probes still leaves a gap the size of a process
     * death between storing the server and storing the pairing, and
     * either side of that gap is a connection the reader did not ask
     * for: a catalog with last week's pairing still attached, or a
     * pairing with no account behind it.
     */
    private suspend fun publishCustom(
        capabilities: ServerCapabilities,
        credentials: RemoteCredentials,
        pairing: ProvedKosyncPairing?,
        syncOnlyUser: String?,
        speaksForKosync: Boolean,
    ) = changingAccount {
        inTransaction {
            storeLocked(
                ServerKind.CUSTOM,
                credentials,
                capabilities,
                keepsPairing = true,
                signedInAs = syncOnlyUser,
            )
            when {
                pairing != null -> kosync().adopt(pairing)
                // A submission that covers the whole connection says by
                // its empty sync half that there is to be no partner.
                // One that covers only the catalog address says nothing
                // about it, and must leave the pairing where it is.
                speaksForKosync -> kosync().forget()
            }
        }
    }

    private suspend fun connect(
        kind: ServerKind,
        url: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
        beforePublish: suspend () -> Unit = {},
    ): SetupResult {
        val setup = setups[kind] ?: return SetupResult.Failure(SetupFailure.WrongServer)
        // What the stored account of the same kind knew about itself.
        // Whether the address is the same is the setup's call: it is
        // the one that normalises the typed URL.
        val prior = dao.get()?.takeIf { it.kind == kind }
            ?.let {
                PriorConnection(
                    baseUrl = it.baseUrl,
                    deviceId = it.liseurDeviceId,
                    // Only a BookOrbit refresh token is offered back, and
                    // only because the setup client checks it against the
                    // candidate address before spending it.
                    refreshToken = it.orbitRefreshCipher?.let(CredentialCipher::decrypt),
                )
            }
        val result = if (prior != null) {
            setup.reconnect(url, credentials, allowHttp, prior)
        } else {
            setup.connect(url, credentials, allowHttp)
        }
        if (result is SetupResult.Success) {
            beforePublish()
            store(kind, credentials, result.capabilities)
        }
        return result
    }

    /**
     * Saves what the probe found, deciding first whether this is the same
     * account coming back or a different one arriving.
     *
     * Reconnecting as the *same person* to the same server is a refresh:
     * keeping the sync token means the next sync asks for what changed
     * rather than replaying the whole library.
     *
     * Signing in as someone else is not. Both servers keep reading state
     * per user, so the previous account's token and sync marker describe
     * a world this login cannot see — reusing them would have this device
     * syncing one person's reading into another person's account.
     */
    private suspend fun store(
        kind: ServerKind,
        credentials: RemoteCredentials,
        capabilities: ServerCapabilities,
    ) {
        changingAccount { inTransaction { storeLocked(kind, credentials, capabilities) } }
    }

    private suspend fun storeLocked(
        kind: ServerKind,
        credentials: RemoteCredentials,
        capabilities: ServerCapabilities,
        /**
         * Whether the caller is already deciding what happens to the
         * KOReader pairing. Only a Custom connection does, because its
         * form holds the sync address: it must be able to publish a
         * pairing here, and clearing one first would undo the write it
         * is in the middle of making.
         */
        keepsPairing: Boolean = false,
        /**
         * Who is connected, when the credential cannot say.
         *
         * Only a sync-only Custom connection needs this: its catalog
         * credential is [RemoteCredentials.Anonymous] because there is
         * no catalog, yet the reader did sign in — to the KOReader sync
         * server. That name is the account here.
         */
        signedInAs: String? = null,
        /**
         * How many books to offer this connection's shelf at, or null
         * to leave whatever it already had. Only the starter card sets
         * it; every other connection walks as much as it can.
         */
        shelfLimit: Int? = null,
    ) {
        val username = signedInAs ?: when (credentials) {
            is RemoteCredentials.Basic -> credentials.username
            is RemoteCredentials.ApiKey -> capabilities.displayName
            // A pasted liseur-sync token does not say whose it is; the
            // token's own name is the honest answer.
            is RemoteCredentials.Bearer ->
                capabilities.displayName.takeIf { it.isNotBlank() }
            // A BookOrbit reconnect signs with a session rather than a
            // password, so the login it was paired with is the honest
            // name. It comes back from `/auth/me` as the display name;
            // the stable account id is what actually tells two logins
            // apart, and it is kept beside this.
            RemoteCredentials.Deferred -> capabilities.displayName.takeIf { it.isNotBlank() }
            // An open catalog has no user to name. Null rather than
            // the catalog's own title, because this column also tells
            // two logins to one server apart, and a name nobody signed
            // in as would make two anonymous connections to different
            // catalogs look like the same account.
            RemoteCredentials.Anonymous -> null
        }
        val stored = dao.get()
        // Once both sides carry a stable account id, it alone decides:
        // a rotated token changes any lesser name, and a capability
        // refresh that signs with a session rather than a password
        // carries the display name instead of the login. Neither must
        // read as a different account, or a refresh retires everything
        // the account had agreed.
        //
        // liseur-sync gained its stable id in a later release; BookOrbit
        // has one from the first connect, so the same rule covers both.
        val stableIdentity = when (stored?.kind) {
            ServerKind.LISEUR_SYNC ->
                stored.liseurAccountId != null && capabilities.liseurAccountId != null

            ServerKind.BOOKORBIT ->
                stored.accountId != null && capabilities.accountId != null

            else -> false
        }
        // A liseur-sync password names the account itself, so the same
        // login to the same server is the same account whatever device
        // id the mint came back with. Reading a changed one as a
        // stranger threw away the cursor and the book names of someone
        // who did nothing but sign in again.
        val namedByLogin = kind == ServerKind.LISEUR_SYNC &&
            credentials is RemoteCredentials.Basic
        val sameAccount = stored != null &&
            stored.kind == kind &&
            // Not compared character for character: the stored
            // spelling of a trailing slash changed between versions,
            // and reading that as a stranger retires the account and
            // takes every book the server has not been asked for yet.
            RemoteUrl.sameAddress(stored.baseUrl, capabilities.baseUrl) &&
            when {
                stableIdentity && stored.kind == ServerKind.LISEUR_SYNC ->
                    stored.liseurAccountId == capabilities.liseurAccountId

                stableIdentity && stored.kind == ServerKind.BOOKORBIT ->
                    // The id outranks the name, so a renamed account or
                    // a session-signed refresh is the same person.
                    stored.accountId == capabilities.accountId

                else -> {
                    // The device id is only a stand-in for an account
                    // the credential cannot name, and it changes for
                    // honest reasons: a server that has forgotten the
                    // one it issued, or one too old to be offered it
                    // back.
                    val sameDevice = namedByLogin ||
                        capabilities.accountId == null ||
                        stored.accountId == capabilities.accountId
                    stored.username == username &&
                        (stored.userId == capabilities.calibreUserId ||
                            capabilities.calibreUserId == null) &&
                        sameDevice
                }
            }
        val existing = stored?.takeIf { sameAccount }

        if (stored != null && !sameAccount) retireForAccountSwitch()
        // A pairing the new server cannot host goes with the switch,
        // rather than sitting there invisibly. Here rather than when the
        // reader starts connecting, because an attempt that fails leaves
        // the old server standing and would otherwise have taken a
        // working Custom pairing with it. This is tidiness: what keeps
        // a stray peer from syncing is that the peer and the foreground
        // policy both ask `hostsKosyncPeer` on every run.
        if (!kind.hostsKosyncPeer && !keepsPairing) kosync().forget()

        val next = RemoteServer(
            kind = kind,
            baseUrl = capabilities.baseUrl,
                catalogUrl = capabilities.catalogUrl,
                username = username,
                passwordCipher = (credentials as? RemoteCredentials.Basic)
                    ?.takeIf { kind.signsWithStoredPassword }
                    ?.let { CredentialCipher.encrypt(it.password) },
                apiKeyCipher = (credentials as? RemoteCredentials.ApiKey)
                    ?.let { CredentialCipher.encrypt(it.key) },
                accountId = capabilities.accountId,
                userId = capabilities.calibreUserId,
                koboTokenCipher = RemoteServer.seal(capabilities.koboToken)
                    ?: existing?.koboTokenCipher,
                canDownload = capabilities.canDownload,
                canManageLibrary = capabilities.canManageLibrary,
                canUpload = capabilities.canUpload,
                canDelete = capabilities.canDelete,
                canReadInsights = capabilities.canReadInsights,
                canAdmin = capabilities.canAdmin,
                // A new BookOrbit password login is a new published
                // connection even when it is the same user. Keep the
                // account's durable state, but give catalog walks and
                // queued work a way to reject answers from the previous
                // connection instance.
                addedAt = if (kind == ServerKind.BOOKORBIT &&
                    credentials is RemoteCredentials.Basic && existing != null
                ) {
                    nextConnectionStamp(existing.addedAt)
                } else {
                    existing?.addedAt ?: System.currentTimeMillis()
                },
                catalogSyncedAt = existing?.catalogSyncedAt,
                positionSyncedAt = existing?.positionSyncedAt,
                syncToken = existing?.syncToken,
                liseurTokenCipher = RemoteServer.seal(capabilities.liseurToken)
                    ?: existing?.liseurTokenCipher,
                // The cursor is the account's memory of what it has
                // reconciled. The same account reconnecting keeps it —
                // that is the difference between resuming and replaying
                // the whole log — and a different account starts at
                // nothing, because its log is a different world.
                syncCursorSeq = existing?.syncCursorSeq ?: 0,
                // The annotation feed has a cursor of its own and the
                // same reasoning. Leaving it out reset it on every token
                // rotation, which replayed the whole annotation log.
                annotationCursorSeq = existing?.annotationCursorSeq ?: 0,
                liseurAccountId = capabilities.liseurAccountId
                    ?: existing?.liseurAccountId,
                // BookOrbit's session. A capability refresh that signed
                // in through `/auth/me` brings no new tokens, so the
                // stored pair stands; a real sign-in replaces it. The
                // epoch is kept for the same account — a token rotation
                // is not a new connection — and minted fresh for a new
                // one, which is what makes a request or a refresh that
                // was in flight for the old account unable to write.
                orbitAccessCipher = RemoteServer.seal(capabilities.orbitAccessToken)
                    ?: existing?.orbitAccessCipher,
                orbitRefreshCipher = RemoteServer.seal(capabilities.orbitRefreshToken)
                    ?: existing?.orbitRefreshCipher,
                orbitAccessExpires = capabilities.orbitAccessExpiresAt
                    .takeIf { it > 0 }
                    ?: existing?.orbitAccessExpires ?: 0,
                orbitSessionId = capabilities.orbitSessionId ?: existing?.orbitSessionId,
                orbitEpoch = if (kind == ServerKind.BOOKORBIT &&
                    credentials is RemoteCredentials.Basic && existing != null
                ) {
                    nextConnectionStamp(existing.orbitEpoch)
                } else {
                    existing?.orbitEpoch ?: System.currentTimeMillis()
                },
                // The reader's choice stands until they make another
                // one. A reconnect that says nothing about the size —
                // an address correction, a refreshed credential — keeps
                // the shelf the size it was offered at.
                //
                // Only while it is the same catalog, though. The size
                // is about the shelf that was picked, and it is also
                // what marks a connection as one this app made and
                // grows rather than prunes; carrying it to a feed the
                // reader has pointed the connection at instead would
                // leave their own catalog unable to let go of a book
                // that was deleted on the server.
                shelfLimit = shelfLimit ?: existing
                    ?.takeIf { row ->
                        val before = row.catalogUrl
                        val after = capabilities.catalogUrl
                        if (before == null || after == null) {
                            before == after
                        } else {
                            RemoteUrl.sameAddress(before, after)
                        }
                    }
                    ?.shelfLimit,
        )
        val written = if (existing != null) carryPeerState(existing, next) else next
        dao.upsert(written)
        if (existing?.kind == ServerKind.BOOKORBIT) {
            if (existing.accountKey != written.accountKey || existing.orbitEpoch != written.orbitEpoch ||
                existing.baseUrl != written.baseUrl
            ) {
                bookOrbitPositionTraversalDao?.clearAccount(existing.accountKey)
            }
            bookOrbitPositionAgreementDao?.rebindConnection(
                written.accountKey, written.orbitEpoch, written.baseUrl,
            )
            bookOrbitStatusAgreementDao?.rebindConnection(
                written.accountKey, written.orbitEpoch, written.baseUrl,
            )
        }
        if (written.kind == ServerKind.BOOKORBIT) relinkUploads(written)
        adoptBookOrbit(written)
    }

    /**
     * Gives uploaded books their server link back when the account that
     * took them signs in again.
     *
     * A disconnect cuts every book loose and keeps the binding of any
     * book that stays on the device. A downloaded book finds its way
     * back through its URL, which is spelled from the server id. An
     * uploaded one keeps its own URL, so without this the catalog would
     * bring it in a second time and offer the local copy for upload
     * again. Bindings are keyed by account, so only the account that
     * took the upload can relink it. Two local books bound to one server
     * book, or a server id some row already holds, are left alone.
     */
    private suspend fun relinkUploads(server: RemoteServer) {
        val bindings = bookOrbitBindingDao ?: return
        val accountId = server.accountId ?: return
        val unlinked = bindings.unlinkedUploads(server.accountKey)
            .groupBy { it.bookId }
            .values
            .mapNotNull { it.singleOrNull() }
            .associateWith { BookOrbitScope.remoteId(server.baseUrl, accountId, it.bookId) }
        if (unlinked.isEmpty()) return
        val held = bookDao.byRemoteUuids(unlinked.values.toList()).mapNotNullTo(HashSet()) { it.remoteUuid }
        unlinked.forEach { (binding, remoteUuid) ->
            val fileId = binding.fileId ?: return@forEach
            if (remoteUuid in held) return@forEach
            bookDao.linkToRemote(
                url = binding.bookUrl,
                remoteUuid = remoteUuid,
                downloadHref = BookOrbitUrl.downloadHref(fileId),
                coverUrl = null,
                remoteUpdatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Points the live session at the row that was just published.
     *
     * Called after the write rather than before, so a connect that fails
     * halfway cannot leave the network layer signing with an account the
     * database does not have. A kind that is not BookOrbit clears
     * whatever was there, which is what makes an account switch stop
     * signing for the old server immediately.
     */
    private fun adoptBookOrbit(server: RemoteServer) {
        val session = bookOrbit ?: return
        if (server.kind != ServerKind.BOOKORBIT) {
            session.clear()
            return
        }
        val access = server.orbitAccessCipher?.let(CredentialCipher::decrypt) ?: return
        val refresh = server.orbitRefreshCipher?.let(CredentialCipher::decrypt) ?: return
        session.adopt(
            server = server,
            access = access,
            accessExpiresAt = server.orbitAccessExpires,
            refresh = refresh,
            sessionId = server.orbitSessionId,
        )
    }

    /** A monotonic-enough persisted stamp, including same-millisecond reconnects. */
    private fun nextConnectionStamp(previous: Long): Long {
        val now = System.currentTimeMillis()
        return if (now > previous) now else previous + 1
    }

    /**
     * Moves what the same account had agreed under its previous key to
     * the key it will have from now on, or keeps the previous key.
     *
     * A liseur-sync account's key changes once: the first time a server
     * that reports the stable account id is reconnected to, when the
     * spelling stops being the device id. Every per-peer table is keyed
     * by that string, and leaving the rows under the old one would
     * strand the baselines, the work names, the cursor's meaning and
     * every unsent annotation — the same loss as an account switch, for
     * a reader who did nothing but reconnect.
     *
     * The new key has never been written, so nothing should be under it.
     * If something is, the move is not made: the row is stored so that
     * it keeps producing the old key, everything stays where it was and
     * the next connect tries again. Guessing which of two rows to keep
     * would be guessing about a reader's unsent position.
     */
    private suspend fun carryPeerState(existing: RemoteServer, next: RemoteServer): RemoteServer {
        val from = existing.accountKey
        val to = next.accountKey
        if (existing.kind != ServerKind.LISEUR_SYNC || from == to) return next
        val occupied = (peerStateDao?.countForPeer(to) ?: 0) +
            (identityDao?.countForPeer(to) ?: 0) +
            (annotationSyncDao?.countForPeer(to) ?: 0) +
            (sessionRefusalDao?.countForPeer(to) ?: 0) +
            (sessionTransmissionDao?.countForPeer(to) ?: 0)
        // The settings baseline is deliberately not counted. It lives in
        // its own store, which commits without waiting for this
        // transaction, so its copy under the new name may be there while
        // everything else is still under the old one. Counting it would
        // read that as somebody else's state and refuse the move for
        // good; it is written over harmlessly if the move runs again.
        if (occupied > 0) {
            Log.w(TAG, "Not moving sync state to a key that already has $occupied rows; keeping the old key")
            return next.copy(liseurAccountId = existing.liseurAccountId)
        }
        peerStateDao?.rekeyPeer(from, to)
        identityDao?.rekeyPeer(from, to)
        annotationSyncDao?.rekeyPeer(from, to)
        uploadRefusalDao?.rekeyAccount(from, to)
        sessionRefusalDao?.rekeyPeer(from, to)
        sessionTransmissionDao?.rekeyPeer(from, to)
        settingsSyncState?.rekeyPeer(from, to)
        progressDao.rekeyAccount(from, to)
        return next
    }

    /**
     * Puts down everything that belonged to the account being left.
     *
     * The saved position stays for books that remain, and so does the note
     * of who wrote it: that is exactly what the reader has to be asked about
     * before anything is handed over. What goes is the agreed baseline and
     * any unsettled remote state, because they describe a library this new
     * login cannot see. Nothing is left looking unsent, so signing in as
     * someone else never quietly uploads the last person's reading into
     * their account.
     *
     * The books go the same way they go on a disconnect. A remote id
     * means something only to the server that issued it, so leaving one
     * behind would have the next sync ask a different server about a
     * book it has never heard of — or, worse, about one of its own that
     * happens to answer to that id.
     */
    private suspend fun retireForAccountSwitch() {
        forgetSyncPeer(dao.get())
        progressDao.retireAccountState()
        bookRemoval.deleteRemoteNotDownloaded()
        bookDao.unlinkDownloadedFromRemote()
        bookOrbitBindingDao?.clearOrphans()
        seriesExtraDao.clear()
    }

    /** Re-runs the probes for the saved account, e.g. after a permission change. */
    suspend fun refreshCapabilities(): SetupResult? {
        val server = dao.get() ?: return null
        val credentials = server.credentials ?: return null
        // Only an account that is *already* on plain HTTP may be probed
        // over it. A stored URL has been through setup, so its scheme is
        // the one the reader agreed to; passing `true` unconditionally
        // would let a briefly unreachable https server be retried in the
        // clear, sending the password there without anyone deciding to.
        val allowHttp = server.baseUrl.startsWith("http://", ignoreCase = true)
        return connect(server.kind, server.baseUrl, credentials, allowHttp = allowHttp)
    }

    /**
     * Forgets an account whose secret can no longer be read.
     *
     * Credentials are encrypted with a key that lives in this device's
     * Keystore and cannot leave it, so a database restored from a backup
     * or moved to a new phone arrives with ciphertext nothing can open.
     * Asking for the password again is better than looking connected
     * while every request quietly fails.
     */
    suspend fun forgetUnreadableAccount(): Boolean = changingAccount {
        val server = dao.get() ?: return@changingAccount false
        if (server.credentials != null) return@changingAccount false
        // Through the same door as a disconnect. This used to drop the
        // row on its own, which left every scrap of per-account state
        // behind — work names, sync agreements, a cursor — for whoever
        // paired next to inherit.
        forgetSyncPeer(server)
        dao.delete()
        true
    }

    /** Sets the Kobo sync token by hand when it could not be picked up. */
    suspend fun setKoboToken(tokenOrUrl: String?) {
        val token = tokenOrUrl?.let {
            CalibreParsing.koboToken(it) ?: it.trim().takeIf { value ->
                value.length == 32 && value.all(Character::isLetterOrDigit)
            }?.lowercase()
        }
        dao.setKoboTokenCipher(RemoteServer.seal(token))
    }

    /**
     * Forgets the account. Books that only ever lived on the server go
     * with it, including its local statistics; anything downloaded stays
     * on the device as an ordinary book with its statistics intact, cut
     * loose so it cannot be synced against a different server later on.
     */
    suspend fun disconnect() {
        // The session is ended on the server before the row that names
        // it goes, because afterwards there is nothing left to log out
        // with. Best effort: a server that cannot be reached must not
        // stop the reader from disconnecting.
        runCatching { bookOrbit?.close() }
        changingAccount {
            inTransaction {
                forgetSyncPeer(dao.get())
                bookRemoval.deleteRemoteNotDownloaded()
                bookDao.unlinkDownloadedFromRemote()
                bookOrbitBindingDao?.clearOrphans()
                // Series summaries belong to the server that wrote them.
                seriesExtraDao.clear()
                dao.delete()
            }
        }
    }

    /**
     * Forgets what a liseur-sync account had agreed or named.
     *
     * The reading itself stays: it is this device's, it was here before
     * any server was, and losing your place in every book because an
     * account was removed would be indefensible. What goes is the record
     * of what that server had confirmed, which means nothing once it is
     * no longer being talked to — and if the same server is connected
     * again as somebody else, their names are theirs, not the last
     * account's.
     *
     * The record of which sittings were uploaded goes with it. It was
     * only ever true of the account they were sent to, and leaving it
     * standing would make the next server look as though it already had
     * a history it has never been told — which is both a dashboard
     * counting reading twice or not at all, and an account that never
     * receives the history it should have.
     */
    private suspend fun forgetSyncPeer(server: RemoteServer?) {
        if (server == null) return
        // Before the kind check, and deliberately: a refusal is not a
        // liseur-sync idea, it is any server's answer, and the row would
        // otherwise outlive the account that produced it.
        uploadRefusalDao?.clearAccount(server.accountKey)
        // Also before the kind check: a starter shelf is a Custom OPDS
        // connection, and how far its catalog had been walked is only
        // true of the account that walked it.
        starterProgressDao?.deleteForAccount(server.accountKey)
        // The live session goes with the account. File bindings do not:
        // a downloaded book keeps its URL and bytes across a disconnect,
        // and the binding is what stops a reconnect from assigning those
        // bytes to whichever EPUB the server calls primary that day.
        // Its namespaced book URL means another account cannot inherit it.
        if (server.kind == ServerKind.BOOKORBIT) {
            bookOrbitCfiDao?.clearAccount(server.accountKey)
            bookOrbitLocalCfiDao?.clearAccount(server.accountKey)
            bookOrbitPositionAgreementDao?.clearAccount(server.accountKey)
            bookOrbitStatusAgreementDao?.clearAccount(server.accountKey)
            bookOrbitPositionTraversalDao?.clearAccount(server.accountKey)
            bookOrbit?.clear()
        }
        if (server.kind != ServerKind.LISEUR_SYNC) return
        peerStateDao?.forgetPeer(server.accountKey)
        identityDao?.forgetPeerAliases(server.accountKey)
        identityDao?.forgetPeerAmbiguities(server.accountKey)
        identityDao?.forgetAnnotationReconciliation(server.accountKey)
        // The marks themselves stay — they are the reader's, and were
        // never the server's to take away. What goes is the record of
        // what this server had confirmed about them: a rev is a number
        // only the server that issued it can read, and offering one to
        // the next account would have it refuse edits over a history it
        // has no part in. The cursor goes for the same reason, so the
        // next account is caught up from the beginning rather than from
        // wherever the last one happened to be.
        annotationSyncDao?.forgetPeer(server.accountKey)
        dao.setAnnotationCursor(0)
        sessionDao?.forgetUploads()
        sessionDao?.forgetTransmissionEvidence()
        sessionTransmissionDao?.clearPeer(server.accountKey)
        sessionRefusalDao?.clearPeer(server.accountKey)
        // What this account agreed each setting was. Timestamps are the
        // issuing server's to interpret, so carrying them to the next
        // one would have every key read as neither newer nor older than
        // itself and go quiet. The reader's settings themselves stay —
        // signing out of a server is not a request to be reconfigured.
        settingsSyncState?.forgetPeer(server.accountKey)
    }

    private companion object {
        const val TAG = "remote-account"

        /**
         * How many times a cold read will retry when the account changes
         * underneath it. A handful, because a change that keeps landing
         * mid-read is a change that will be read correctly a moment
         * later anyway.
         */
        const val LOAD_ATTEMPTS = 3
    }
}
