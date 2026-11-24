package com.chmouel.liseur.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact local library counts shown before seeding or switching to a Google Drive account. */
data class LocalLibrarySummary(
    val booksCount: Int,
    val notesCount: Int,
    val readingSessionsCount: Int,
) {
    val hasLocalData: Boolean
        get() = booksCount > 0 || notesCount > 0 || readingSessionsCount > 0
}

/**
 * Confirmation gate required by ADR-0078 before writing anything to or
 * activating [targetPermissionId] when switching accounts, connecting with
 * existing local library data, or disconnecting a main sync server.
 */
data class PendingAccountSwitchConfirmation(
    val previousAccount: DriveAccount?,
    val previousState: String?,
    val targetPermissionId: String,
    val targetEmail: String,
    val localSummary: LocalLibrarySummary,
    val mainServerConnected: Boolean,
) {
    val isAccountSwitch: Boolean
        get() = previousAccount != null && previousAccount.permissionId != targetPermissionId
}

/** Snapshot rendered by Play's Google Drive Sync Settings UI. */
data class DriveSettingsUiState(
    val account: DriveAccount? = null,
    val pausedAccounts: List<DriveAccount> = emptyList(),
    val pendingUploadsCount: Int = 0,
    val unresolvedConflictsCount: Int = 0,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val pendingConfirmation: PendingAccountSwitchConfirmation? = null,
    val pendingAuthIntent: PendingIntent? = null,
)

/** Result of silent background token acquisition for Drive workers. */
sealed interface SilentTokenResult {
    data class Token(val accessToken: String, val permissionId: String) : SilentTokenResult
    data class NeedsReconnect(val permissionId: String, val reason: String) : SilentTokenResult
    data class TransientFailure(val reason: String) : SilentTokenResult
    data object Inactive : SilentTokenResult
}

/**
 * Implements ADR-0078's Google Drive authorization & account-switch state machine.
 *
 * Guarantees:
 * - Every Drive account is keyed by Google Drive's immutable `permissionId`
 *   from `about.get?fields=user(permissionId,emailAddress)` (never by email).
 * - Connecting Account B while Account A exists or local library data exists
 *   pauses Account A (preserving all of A's cursors, queues, folder mappings,
 *   and conflicts) and requires explicit confirmation naming Account B and
 *   summarizing local library counts BEFORE activating B or writing to B.
 * - Background silent re-auth pauses Drive workers and marks the account
 *   `NEEDS_RECONNECT` whenever Play Services returns `hasResolution()` or
 *   revoked consent.
 */
internal class DriveAccountController(
    private val database: LiseurDatabase,
    private val remoteAccount: RemoteAccountRepository,
    private val driveSync: DriveLibrarySync,
    private val authClient: DriveAuthClient,
    private val driveApi: DriveRemoteApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val transitionMutex = Mutex()

    /** Bumped under [transitionMutex] by every account transition, so silent refreshes can detect they went stale. */
    @Volatile
    private var accountGeneration = 0L
    private val ephemeral = MutableStateFlow(EphemeralState())

    /**
     * Transient access token held only in memory while awaiting confirmation
     * for Account B; discarded immediately on cancel or after confirmation.
     */
    @Volatile
    private var pendingAccessToken: String? = null

    private data class EphemeralState(
        val busy: Boolean = false,
        val errorMessage: String? = null,
        val pendingConfirmation: PendingAccountSwitchConfirmation? = null,
        val pendingAuthIntent: PendingIntent? = null,
    )

    private data class StoreSnapshot(
        val account: DriveAccount? = null,
        val pausedAccounts: List<DriveAccount> = emptyList(),
        val pendingUploadsCount: Int = 0,
        val unresolvedConflictsCount: Int = 0,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: Flow<DriveSettingsUiState> = driveSync.storeAvailable
        .distinctUntilChanged()
        .flatMapLatest { available ->
            if (!available) {
                flowOf(StoreSnapshot())
            } else {
                val db = driveSync.store
                combine(
                    db.accounts().observePrimary(),
                    db.accounts().observeAll(),
                ) { primary, all ->
                    primary to all
                }.flatMapLatest { (primary, all) ->
                    val paused = all.filter { it.state == DriveAccount.STATE_PAUSED }
                    if (primary == null) {
                        flowOf(StoreSnapshot(account = null, pausedAccounts = paused))
                    } else {
                        combine(
                            db.uploads().observePendingCount(primary.permissionId),
                            db.conflicts().observeOpenCount(primary.permissionId),
                        ) { pendingUploads, openConflicts ->
                            StoreSnapshot(
                                account = primary,
                                pausedAccounts = paused,
                                pendingUploadsCount = pendingUploads,
                                unresolvedConflictsCount = openConflicts,
                            )
                        }
                    }
                }
            }
        }
        .combine(ephemeral) { storeSnapshot, ui ->
            DriveSettingsUiState(
                account = storeSnapshot.account,
                pausedAccounts = storeSnapshot.pausedAccounts,
                pendingUploadsCount = storeSnapshot.pendingUploadsCount,
                unresolvedConflictsCount = storeSnapshot.unresolvedConflictsCount,
                busy = ui.busy,
                errorMessage = ui.errorMessage,
                pendingConfirmation = ui.pendingConfirmation,
                pendingAuthIntent = ui.pendingAuthIntent,
            )
        }
        .distinctUntilChanged()

    /**
     * Starts an interactive Google Drive connect, reconnect, or account-switch flow.
     *
     * @param switchAccount When true, asks Play Services for the account chooser
     *   so the reader can select a different Google account.
     */
    suspend fun beginConnect(switchAccount: Boolean = false) {
        transitionMutex.withLock {
            if (ephemeral.value.busy || ephemeral.value.pendingConfirmation != null) return
            ephemeral.value = ephemeral.value.copy(
                busy = true,
                errorMessage = null,
                pendingAuthIntent = null,
            )
            var awaitingActivityResult = false
            try {
                val targetEmail = if (switchAccount || !driveSync.hasStore()) {
                    null
                } else {
                    driveSync.store.accounts().all()
                        .sortedWith(PRIMARY_ACCOUNT_ORDER)
                        .firstOrNull()
                        ?.email
                        ?.takeIf { it.isNotBlank() }
                }

                when (val result = authClient.authorize(accountEmail = targetEmail, selectAccount = switchAccount)) {
                    is DriveAuthResult.Authorized -> handleAuthorizedTokenLocked(result.accessToken)
                    is DriveAuthResult.NeedsResolution -> {
                        if (result.pendingIntent != null) {
                            awaitingActivityResult = true
                            ephemeral.value = ephemeral.value.copy(
                                busy = true,
                                pendingAuthIntent = result.pendingIntent,
                            )
                        } else {
                            ephemeral.value = ephemeral.value.copy(
                                busy = false,
                                errorMessage = "Google Play Services could not open the sign-in prompt",
                            )
                        }
                    }
                    is DriveAuthResult.Failed -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessage = result.reason,
                        )
                    }
                }
            } catch (e: CancellationException) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    pendingAuthIntent = null,
                )
                throw e
            } finally {
                if (!awaitingActivityResult && ephemeral.value.busy) {
                    ephemeral.value = ephemeral.value.copy(
                        busy = false,
                        pendingAuthIntent = null,
                    )
                }
            }
        }
    }

    /** Clears the one-shot [PendingIntent] once the UI has launched it. */
    fun consumePendingAuthIntent() {
        ephemeral.value = ephemeral.value.copy(pendingAuthIntent = null)
    }

    /** Handles the result of launching Play Services' interactive authorization [PendingIntent]. */
    suspend fun onAuthorizationActivityResult(resultCode: Int, data: Intent?) {
        transitionMutex.withLock {
            ephemeral.value = ephemeral.value.copy(pendingAuthIntent = null)
            try {
                if (resultCode != Activity.RESULT_OK || data == null) {
                    ephemeral.value = ephemeral.value.copy(busy = false)
                    return
                }
                when (val parsed = authClient.parseAuthorizationResult(data)) {
                    is DriveAuthResult.Authorized -> handleAuthorizedTokenLocked(parsed.accessToken)
                    is DriveAuthResult.NeedsResolution -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessage = "Google Drive authorization did not complete",
                        )
                    }
                    is DriveAuthResult.Failed -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessage = parsed.reason,
                        )
                    }
                }
            } catch (e: CancellationException) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    pendingAuthIntent = null,
                )
                throw e
            } finally {
                if (ephemeral.value.busy) {
                    ephemeral.value = ephemeral.value.copy(
                        busy = false,
                        pendingAuthIntent = null,
                    )
                }
            }
        }
    }

    /** Processes an access token obtained from interactive authorization. */
    suspend fun onAccessTokenObtained(accessToken: String) {
        transitionMutex.withLock {
            ephemeral.value = ephemeral.value.copy(busy = true, errorMessage = null)
            try {
                handleAuthorizedTokenLocked(accessToken)
            } catch (e: CancellationException) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    pendingAuthIntent = null,
                )
                throw e
            } finally {
                if (ephemeral.value.busy) {
                    ephemeral.value = ephemeral.value.copy(
                        busy = false,
                        pendingAuthIntent = null,
                    )
                }
            }
        }
    }

    /**
     * Confirms a pending account switch or initial local-data sync.
     *
     * Keeps any previous Account A `PAUSED` with its cursors, queues, and
     * conflicts intact, and activates Account B.
     */
    suspend fun confirmPendingSwitch() {
        transitionMutex.withLock {
            accountGeneration++
            val pending = ephemeral.value.pendingConfirmation ?: return
            ephemeral.value = ephemeral.value.copy(busy = true, errorMessage = null)
            try {
                // Consent covers only the server state the reader was shown; one that
                // connected since needs a fresh confirmation.
                driveSync.activateAccount(
                    permissionId = pending.targetPermissionId,
                    email = pending.targetEmail,
                    now = clock(),
                    confirmedDisconnectServer = pending.mainServerConnected,
                )
                pendingAccessToken = null
                ephemeral.value = EphemeralState()
                driveSync.notifyActivated()
            } catch (e: CancellationException) {
                ephemeral.value = ephemeral.value.copy(busy = false)
                throw e
            } catch (e: MainServerConnectedException) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    pendingConfirmation = pending.copy(mainServerConnected = true),
                )
            } catch (e: Exception) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    errorMessage = e.message ?: "Could not activate Google Drive account",
                )
            }
        }
    }

    /**
     * Cancels a pending account switch or initial local-data sync.
     *
     * Discards the transient token for Account B and restores Account A's
     * previous state (`ACTIVE` / `NEEDS_RECONNECT` / `PAUSED`). Zero writes
     * occur on Account B.
     */
    suspend fun cancelPendingSwitch() {
        transitionMutex.withLock {
            accountGeneration++
            val pending = ephemeral.value.pendingConfirmation ?: return
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
            val previous = pending.previousAccount
            val previousState = pending.previousState
            if (previous != null && previousState != null && driveSync.hasStore()) {
                driveSync.restoreAccountState(previous.permissionId, previousState)
            }
        }
    }

    /** Disconnects Google Drive, pausing workers and keeping all local Drive state intact. */
    suspend fun disconnect() {
        transitionMutex.withLock {
            accountGeneration++
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
            driveSync.deactivate()
        }
    }

    /** Dismisses a transient error banner in the UI. */
    fun clearError() {
        ephemeral.value = ephemeral.value.copy(errorMessage = null)
    }

    /**
     * Acquires an access token silently for background Drive sync.
     *
     * If Play Services indicates interactive consent is needed (`hasResolution()`)
     * or access was revoked, pauses background work and transitions the active
     * account to `NEEDS_RECONNECT` while preserving all cursors and queues.
     */
    suspend fun acquireSilentAccessToken(staleTokenToClear: String? = null): SilentTokenResult {
        if (!driveSync.hasStore()) return SilentTokenResult.Inactive
        // Snapshot under the transition lock so a refresh never starts from a half-done transition.
        val (generation, active) = transitionMutex.withLock {
            accountGeneration to driveSync.store.accounts().active()
        }
        if (active == null) return SilentTokenResult.Inactive
        if (!staleTokenToClear.isNullOrBlank()) {
            authClient.clearCachedToken(staleTokenToClear)
        }
        return when (val result = authClient.authorizeSilently(accountEmail = active.email)) {
            is DriveAuthResult.Authorized -> {
                val about = try {
                    driveApi.getAbout(result.accessToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveAuthExpiredException) {
                    authClient.clearCachedToken(result.accessToken)
                    val reason = e.message ?: RECONNECT_REQUIRED_REASON
                    return reconnectIfCurrent(active.permissionId, generation, reason)
                } catch (e: Exception) {
                    return SilentTokenResult.TransientFailure(
                        e.message ?: "Could not verify Google Drive account",
                    )
                }
                if (about.permissionId != active.permissionId) {
                    authClient.clearCachedToken(result.accessToken)
                    reconnectIfCurrent(active.permissionId, generation, ACCOUNT_MISMATCH_REASON)
                } else {
                    transitionMutex.withLock {
                        if (isCurrent(active.permissionId, generation)) {
                            SilentTokenResult.Token(
                                accessToken = result.accessToken,
                                permissionId = active.permissionId,
                            )
                        } else {
                            SilentTokenResult.TransientFailure(STALE_ACCOUNT_REASON)
                        }
                    }
                }
            }
            is DriveAuthResult.NeedsResolution -> {
                reconnectIfCurrent(active.permissionId, generation, RECONNECT_REQUIRED_REASON)
            }
            is DriveAuthResult.Failed -> {
                if (result.requiresReconnect) {
                    reconnectIfCurrent(active.permissionId, generation, result.reason)
                } else {
                    SilentTokenResult.TransientFailure(result.reason)
                }
            }
        }
    }


    private suspend fun isCurrent(permissionId: String, generation: Long): Boolean =
        generation == accountGeneration && driveSync.store.accounts().active()?.permissionId == permissionId

    /**
     * Marks [permissionId] `NEEDS_RECONNECT` only if the activation the silent refresh
     * started from is still current; a refresh that raced a switch, disconnect or
     * reconnect must not touch the account that replaced it.
     */
    private suspend fun reconnectIfCurrent(
        permissionId: String,
        generation: Long,
        reason: String,
    ): SilentTokenResult = transitionMutex.withLock {
        if (generation == accountGeneration && driveSync.markNeedsReconnectIfActive(permissionId, reason)) {
            SilentTokenResult.NeedsReconnect(permissionId, reason)
        } else {
            SilentTokenResult.TransientFailure(STALE_ACCOUNT_REASON)
        }
    }

    /**
     * Handles an HTTP 401 (`DriveAuthExpiredException`) from Drive REST calls:
     * clears the rejected cached token and tries silent re-auth once; if consent
     * was revoked or requires UI, transitions the account to `NEEDS_RECONNECT`.
     */
    suspend fun handleAuthExpired(failedAccessToken: String): SilentTokenResult =
        acquireSilentAccessToken(staleTokenToClear = failedAccessToken)

    /** Reads exact local library counts from `liseur.db` for the confirmation dialog. */
    internal suspend fun readLocalLibrarySummary(): LocalLibrarySummary {
        val books = database.bookDao().allOnce().count { it.browseServerId == null || it.localUri != null }
        val notes = database.annotationDao().all().size
        val sessions = database.readingSessionDao().closedForBackup().size
        return LocalLibrarySummary(
            booksCount = books,
            notesCount = notes,
            readingSessionsCount = sessions,
        )
    }

    private suspend fun handleAuthorizedTokenLocked(accessToken: String) {
        accountGeneration++
        val about = try {
            driveApi.getAbout(accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthExpiredException) {
            authClient.clearCachedToken(accessToken)
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessage = e.message ?: "Google Drive rejected the authorization token",
            )
            return
        } catch (e: Exception) {
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessage = e.message ?: "Could not reach Google Drive",
            )
            return
        }

        val existingAccounts = if (driveSync.hasStore()) {
            driveSync.store.accounts().all()
        } else {
            emptyList()
        }
        val primaryExisting = existingAccounts.sortedWith(PRIMARY_ACCOUNT_ORDER).firstOrNull()
        val isSwitchingFromOtherAccount = primaryExisting != null &&
            primaryExisting.permissionId != about.permissionId
        val isReconnectingSamePrimary = primaryExisting != null &&
            primaryExisting.permissionId == about.permissionId

        val localSummary = readLocalLibrarySummary()
        val mainServerConnected = remoteAccount.current() != null

        val needsConfirmation = isSwitchingFromOtherAccount ||
            mainServerConnected ||
            (!isReconnectingSamePrimary && localSummary.hasLocalData)

        if (needsConfirmation) {
            val previousState = primaryExisting?.state
            // Keep Account A PAUSED (and stop its workers) while awaiting confirmation,
            // preserving all of A's cursors, queues, folder mappings, and conflicts.
            if (primaryExisting != null && previousState != DriveAccount.STATE_PAUSED) {
                driveSync.deactivate()
            }
            pendingAccessToken = accessToken
            ephemeral.value = EphemeralState(
                busy = false,
                pendingConfirmation = PendingAccountSwitchConfirmation(
                    previousAccount = primaryExisting,
                    previousState = previousState,
                    targetPermissionId = about.permissionId,
                    targetEmail = about.emailAddress.ifBlank { about.permissionId },
                    localSummary = localSummary,
                    mainServerConnected = mainServerConnected,
                ),
            )
            return
        }

        try {
            driveSync.activateAccount(
                permissionId = about.permissionId,
                email = about.emailAddress.ifBlank { about.permissionId },
                now = clock(),
                confirmedDisconnectServer = false,
            )
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
            driveSync.notifyActivated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MainServerConnectedException) {
            // A server connected between the check above and activation: ask the reader
            // again instead of disconnecting it silently.
            pendingAccessToken = accessToken
            ephemeral.value = EphemeralState(
                busy = false,
                pendingConfirmation = PendingAccountSwitchConfirmation(
                    previousAccount = primaryExisting,
                    previousState = primaryExisting?.state,
                    targetPermissionId = about.permissionId,
                    targetEmail = about.emailAddress.ifBlank { about.permissionId },
                    localSummary = localSummary,
                    mainServerConnected = true,
                ),
            )
        } catch (e: Exception) {
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessage = e.message ?: "Could not activate Google Drive account",
            )
        }
    }

    companion object {
        const val RECONNECT_REQUIRED_REASON = "Google Drive access needs to be reconnected"
        const val STALE_ACCOUNT_REASON = "Google Drive account changed during authorization"
        const val ACCOUNT_MISMATCH_REASON =
            "Google Drive returned a different account; reconnect to confirm"

        private val PRIMARY_ACCOUNT_ORDER: Comparator<DriveAccount> = compareBy<DriveAccount>(
            {
                when (it.state) {
                    DriveAccount.STATE_ACTIVE -> 0
                    DriveAccount.STATE_NEEDS_RECONNECT -> 1
                    else -> 2
                }
            },
            { -it.connectedAt },
        )
    }
}
