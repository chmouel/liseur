package com.chmouel.liseur.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.seriesKey
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Exact local library counts shown before seeding or switching to a Google Drive account. */
data class LocalLibrarySummary(
    val booksCount: Int,
    val notesCount: Int,
    val readingSessionsCount: Int,
    val vocabularyWordsCount: Int = 0,
) {
    val hasLocalData: Boolean
        get() = booksCount > 0 || notesCount > 0 || readingSessionsCount > 0 || vocabularyWordsCount > 0
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
    /** The address Drive reported for the target, blank when Drive omitted it. */
    val targetEmail: String,
    val localSummary: LocalLibrarySummary,
    val mainServerConnected: Boolean,
    /** The connection the reader was shown; activation re-asks if another one replaced it. */
    val confirmedServer: ServerConnectionStamp? = null,
) {
    val isAccountSwitch: Boolean
        get() = previousAccount != null && previousAccount.permissionId != targetPermissionId

    /** Names the target account when Drive returned no email address for it. */
    val targetDisplayName: String get() = targetEmail.ifBlank { targetPermissionId }
}

/** Display model for one unresolved conflict (or one shelf-grouped series conflict). */
data class DriveConflictUiGroup(
    val groupId: String,
    val domain: String,
    val kind: String,
    val bookTitle: String,
    val fieldLabel: String?,
    val localSummary: String,
    val remoteSummary: String,
    val localDeleted: Boolean,
    val remoteDeleted: Boolean,
    val conflicts: List<DriveConflict>,
) {
    /** Series shelves always choose between device orders; Keep edited/Delete applies to other domains. */
    val offersKeepEditedOrDelete: Boolean
        get() = kind == DriveConflict.KIND_DELETE_EDIT && domain != DriveWireCodec.DOMAIN_SERIES
}

/** Snapshot rendered by Play's Google Drive Sync Settings UI. */
data class DriveSettingsUiState(
    val account: DriveAccount? = null,
    val pausedAccounts: List<DriveAccount> = emptyList(),
    val pendingUploadsCount: Int = 0,
    val unresolvedConflictsCount: Int = 0,
    val unresolvedConflictGroups: List<DriveConflictUiGroup> = emptyList(),
    val conflictsDialogVisible: Boolean = false,
    val resolvingConflict: Boolean = false,
    val pausedSchemaDeviceLabels: List<String> = emptyList(),
    val pendingWorkMatches: List<DrivePendingWorkMatch> = emptyList(),
    val meteredTransfers: Boolean = false,
    val uploadPickerVisible: Boolean = false,
    val uploadCandidates: List<DriveUploadCandidate> = emptyList(),
    val syncingNow: Boolean = false,
    val busy: Boolean = false,
    @param:StringRes val errorMessageRes: Int? = null,
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

    /** True only while an interactive authorization result is expected; anything else is stale. */
    @Volatile
    private var awaitingAuthorizationResult = false

    /**
     * Transient access token held only in memory while awaiting confirmation
     * for Account B; discarded immediately on cancel or after confirmation.
     */
    @Volatile
    private var pendingAccessToken: String? = null

    private data class EphemeralState(
        val busy: Boolean = false,
        @param:StringRes val errorMessageRes: Int? = null,
        val errorMessage: String? = null,
        val syncingNow: Boolean = false,
        val uploadPickerVisible: Boolean = false,
        val uploadCandidates: List<DriveUploadCandidate> = emptyList(),
        val conflictsDialogVisible: Boolean = false,
        val resolvingConflict: Boolean = false,
        val pendingConfirmation: PendingAccountSwitchConfirmation? = null,
        val pendingAuthIntent: PendingIntent? = null,
    )

    private data class StoreSnapshot(
        val account: DriveAccount? = null,
        val pausedAccounts: List<DriveAccount> = emptyList(),
        val pendingUploadsCount: Int = 0,
        val unresolvedConflictsCount: Int = 0,
        val unresolvedConflictGroups: List<DriveConflictUiGroup> = emptyList(),
        val pausedSchemaDeviceLabels: List<String> = emptyList(),
        val pendingWorkMatches: List<DrivePendingWorkMatch> = emptyList(),
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
                        val peerId = DriveLibrarySync.accountPeerId(primary.permissionId)
                        // Both work-identity counters only exist to wake the snapshot up.
                        val workIdentityTriggers = combine(
                            database.workIdentityDao().observeAmbiguityCount(peerId),
                            database.workIdentityDao().observeAwaitingAnswer(peerId),
                        ) { ambiguity, awaiting -> ambiguity to awaiting }
                        combine(
                            db.uploads().observePendingCount(primary.permissionId),
                            db.conflicts().observeOpen(primary.permissionId),
                            db.cursors().observePausedForAccount(primary.permissionId),
                            workIdentityTriggers,
                            // Conflict cards depend on whether their book is on the shelf, so a book
                            // being added or removed has to rebuild them on its own.
                            database.bookDao().observeAll(),
                        ) { pendingUploads, openConflicts, pausedCursors, _, _ ->
                            val pausedLabels = pausedCursors
                                .map { it.deviceId }
                                .distinct()
                                .map { devId ->
                                    db.devices().get(primary.permissionId, devId)?.label
                                        ?.takeIf { it.isNotBlank() } ?: devId
                                }
                            val matches = runCatching {
                                driveSync.workIdentity.pendingMatches(primary.permissionId)
                            }.getOrDefault(emptyList())
                            val conflictGroups = runCatching {
                                buildConflictUiGroups(openConflicts)
                            }.getOrDefault(emptyList())
                            StoreSnapshot(
                                account = primary,
                                pausedAccounts = paused,
                                pendingUploadsCount = pendingUploads,
                                unresolvedConflictsCount = conflictGroups.size,
                                unresolvedConflictGroups = conflictGroups,
                                pausedSchemaDeviceLabels = pausedLabels,
                                pendingWorkMatches = matches,
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
                unresolvedConflictGroups = storeSnapshot.unresolvedConflictGroups,
                conflictsDialogVisible = ui.conflictsDialogVisible && storeSnapshot.unresolvedConflictGroups.isNotEmpty(),
                resolvingConflict = ui.resolvingConflict,
                pausedSchemaDeviceLabels = storeSnapshot.pausedSchemaDeviceLabels,
                pendingWorkMatches = storeSnapshot.pendingWorkMatches,
                meteredTransfers = storeSnapshot.account?.meteredTransfers == true,
                uploadPickerVisible = ui.uploadPickerVisible,
                uploadCandidates = ui.uploadCandidates,
                syncingNow = ui.syncingNow,
                busy = ui.busy,
                errorMessageRes = ui.errorMessageRes,
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
                errorMessageRes = null,
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
                            awaitingAuthorizationResult = true
                            ephemeral.value = ephemeral.value.copy(
                                busy = true,
                                pendingAuthIntent = result.pendingIntent,
                            )
                        } else {
                            ephemeral.value = ephemeral.value.copy(
                                busy = false,
                                errorMessageRes = R.string.drive_sync_error_sign_in_prompt,
                            )
                        }
                    }
                    is DriveAuthResult.Failed -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessageRes = R.string.drive_sync_error_auth_failed,
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

    /**
     * Drops an interactive authorization whose result can no longer be delivered
     * (the settings section left composition), freeing the controller for a retry.
     * A result that still arrives later is ignored.
     */
    fun abandonAuthorization() {
        if (!awaitingAuthorizationResult) return
        awaitingAuthorizationResult = false
        ephemeral.update { it.copy(busy = false, pendingAuthIntent = null) }
    }

    /**
     * Drops any interactive authorization or pending switch confirmation when the
     * settings section leaves composition on a non-configuration disposal, restoring
     * any paused account on the backend's scope so it completes after UI disposal.
     */
    fun abandonInteractiveFlow(scope: CoroutineScope = driveSync.scope) {
        abandonAuthorization()
        if (ephemeral.value.pendingConfirmation != null) {
            scope.launch { cancelPendingSwitch() }
        }
    }

    /** Handles the result of launching Play Services' interactive authorization [PendingIntent]. */
    suspend fun onAuthorizationActivityResult(resultCode: Int, data: Intent?) {
        transitionMutex.withLock {
            Log.i(TAG, "Authorization activity returned code=$resultCode, hasData=${data != null}, awaiting=$awaitingAuthorizationResult")
            if (!awaitingAuthorizationResult) return
            awaitingAuthorizationResult = false
            ephemeral.value = ephemeral.value.copy(pendingAuthIntent = null)
            try {
                if (data == null) {
                    ephemeral.value = ephemeral.value.copy(busy = false)
                    return
                }
                val parsed = authClient.parseAuthorizationResult(data)
                when (parsed) {
                    is DriveAuthResult.Authorized -> Log.i(TAG, "Authorization response contained a token")
                    is DriveAuthResult.NeedsResolution -> Log.i(TAG, "Authorization response requires another resolution")
                    is DriveAuthResult.Failed -> Log.w(
                        TAG,
                        "Authorization response failed: status=${(parsed.cause as? ApiException)?.statusCode}, " +
                            "type=${parsed.cause?.javaClass?.simpleName}",
                    )
                }
                if (resultCode != Activity.RESULT_OK) {
                    val failed = parsed as? DriveAuthResult.Failed
                    val status = (failed?.cause as? ApiException)?.statusCode
                    ephemeral.value = ephemeral.value.copy(
                        busy = false,
                        errorMessageRes = if (failed != null && status != CommonStatusCodes.CANCELED) {
                            R.string.drive_sync_error_auth_failed
                        } else {
                            null
                        },
                    )
                    return
                }
                when (parsed) {
                    is DriveAuthResult.Authorized -> handleAuthorizedTokenLocked(parsed.accessToken)
                    is DriveAuthResult.NeedsResolution -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessageRes = R.string.drive_sync_error_auth_incomplete,
                        )
                    }
                    is DriveAuthResult.Failed -> {
                        ephemeral.value = ephemeral.value.copy(
                            busy = false,
                            errorMessageRes = R.string.drive_sync_error_auth_failed,
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
            ephemeral.value = ephemeral.value.copy(busy = true, errorMessageRes = null)
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
            ephemeral.value = ephemeral.value.copy(busy = true, errorMessageRes = null)
            try {
                // Consent covers only the server state the reader was shown; one that
                // connected since needs a fresh confirmation.
                driveSync.activateAccount(
                    permissionId = pending.targetPermissionId,
                    email = pending.targetEmail,
                    now = clock(),
                    confirmedDisconnectServer = pending.mainServerConnected,
                    confirmedServer = pending.confirmedServer,
                )
                pendingAccessToken = null
                ephemeral.value = EphemeralState()
                driveSync.notifyActivated()
                presentInitialUploadSelection()
            } catch (e: CancellationException) {
                ephemeral.value = ephemeral.value.copy(busy = false)
                throw e
            } catch (e: MainServerConnectedException) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    pendingConfirmation = pending.copy(
                        mainServerConnected = true,
                        confirmedServer = e.server,
                    ),
                )
            } catch (_: Exception) {
                ephemeral.value = ephemeral.value.copy(
                    busy = false,
                    errorMessageRes = R.string.drive_sync_error_activate_failed,
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
            val previous = pending.previousAccount
            val previousState = pending.previousState
            // Restore first: cancelling this coroutine mid-restore must leave the
            // confirmation in place, so the reader can still retry instead of
            // being left with a paused account and nothing pending.
            if (previous != null && previousState != null && driveSync.hasStore()) {
                driveSync.restoreAccountState(previous.permissionId, previousState)
            }
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
        }
    }

    /** Disconnects Google Drive, pausing workers and keeping all local Drive state intact. */
    suspend fun disconnect() {
        transitionMutex.withLock {
            awaitingAuthorizationResult = false
            accountGeneration++
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
            driveSync.deactivate()
        }
    }

    /** Dismisses a transient error banner in the UI. */
    fun clearError() {
        ephemeral.value = ephemeral.value.copy(errorMessageRes = null)
    }

    /** Triggers an immediate `drive.appdata` sync cycle. */
    suspend fun syncNow() {
        if (ephemeral.value.syncingNow) return
        ephemeral.value = ephemeral.value.copy(syncingNow = true, errorMessage = null)
        try {
            driveSync.changeEngine.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ephemeral.value = ephemeral.value.copy(
                errorMessage = e.message ?: "Google Drive sync failed",
            )
        } finally {
            ephemeral.value = ephemeral.value.copy(syncingNow = false)
        }
    }

    /** Opens the Play-only "Upload EPUB files" selection UI for the active Drive account. */
    suspend fun openUploadPicker() {
        if (!driveSync.hasStore()) return
        val active = driveSync.store.accounts().active() ?: return
        val candidates = driveSync.epubTransfers.listUploadCandidates(active.permissionId)
        ephemeral.value = ephemeral.value.copy(
            uploadPickerVisible = true,
            uploadCandidates = candidates,
        )
    }

    /** Closes the "Upload EPUB files" selection UI without modifying the upload queue. */
    fun closeUploadPicker() {
        ephemeral.value = ephemeral.value.copy(uploadPickerVisible = false)
    }

    /**
     * Asks for the selection ADR-0078 requires before the first EPUB goes up on a newly
     * activated account; the offer stays off until it is answered.
     */
    private suspend fun presentInitialUploadSelection() {
        val active = if (driveSync.hasStore()) driveSync.store.accounts().active() else null
        if (active != null && active.uploadSelectionAt == null) openUploadPicker()
    }

    /** Updates whether bulk EPUB transfers may use metered mobile networks (default: unmetered only). */
    suspend fun setMeteredTransfers(allowMetered: Boolean) {
        if (!driveSync.hasStore()) return
        val active = driveSync.store.accounts().active() ?: return
        driveSync.store.accounts().setMeteredTransfers(active.permissionId, allowMetered)
        if (driveSync.store.uploads().workCount(active.permissionId) > 0) {
            driveSync.triggerEpubUploads(allowMetered)
        }
    }

    /** Reconciles the upload queue with the picker selection and schedules the bulk upload worker. */
    suspend fun confirmUploadSelection(selectedBookUrls: Set<String>) {
        if (!driveSync.hasStore()) return
        val (generation, active) = transitionMutex.withLock {
            accountGeneration to driveSync.store.accounts().active()
        }
        if (active == null) return
        // The cutoff and the rows the answer drops are one write, so an ALWAYS emission holding
        // the old cutoff cannot pair with rows the picker is about to deselect, and a worker
        // starting in between cannot upload a book this answer left out.
        val queued = driveSync.epubTransfers.queueSelectedBooks(active.permissionId, selectedBookUrls, clock())
        // Fingerprinting can outlast an account switch. The answer's rows belong to its own
        // account and stay written, but the picker and the shared worker now belong to the next one.
        transitionMutex.withLock {
            if (generation != accountGeneration) return
            ephemeral.value = ephemeral.value.copy(uploadPickerVisible = false)
            if (queued > 0 || driveSync.store.uploads().workCount(active.permissionId) > 0) {
                // Also covers cleanup of uploads just cancelled: the replaced worker resolves their sessions.
                driveSync.triggerEpubUploads(active.meteredTransfers)
            } else {
                driveSync.cancelEpubUploads()
            }
        }
    }

    /**
     * Takes the picker's answer off the settings screen's hands. The write, the
     * fingerprinting and the worker it schedules run on [driveSync.scope], because
     * leaving Settings mid-way must not drop a selection the reader already made.
     */
    fun submitUploadSelection(selectedBookUrls: Set<String>) {
        driveSync.scope.launch { confirmUploadSelection(selectedBookUrls) }
    }

    /** Opens the Play-only Conflict Resolution dialog. */
    fun openConflictsDialog() {
        ephemeral.value = ephemeral.value.copy(conflictsDialogVisible = true)
    }

    /** Closes the Play-only Conflict Resolution dialog. */
    fun closeConflictsDialog() {
        ephemeral.value = ephemeral.value.copy(conflictsDialogVisible = false)
    }

    /** Resolves one conflict or shelf-grouped series conflict and publishes `op: "resolve"`. */
    suspend fun resolveConflictGroup(group: DriveConflictUiGroup, choice: DriveConflictChoice) {
        if (ephemeral.value.resolvingConflict) return
        ephemeral.value = ephemeral.value.copy(resolvingConflict = true, errorMessage = null)
        try {
            driveSync.resolveConflicts(group.conflicts, choice, syncAfter = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ephemeral.value = ephemeral.value.copy(
                errorMessage = e.message ?: "Could not resolve Google Drive conflict",
            )
        } finally {
            ephemeral.value = ephemeral.value.copy(resolvingConflict = false)
        }
    }

    /** Deletes [book] from the Google Drive synced library (durable tombstone + Drive EPUB trash). */
    suspend fun deleteFromSyncedLibrary(book: Book): Boolean =
        driveSync.deleteFromSyncedLibrary(book)

    /** Builds UI conflict cards, grouping `series` conflicts by `seriesKey` per `docs/DRIVE_SYNC.md` §4.4. */
    internal suspend fun buildConflictUiGroups(openConflicts: List<DriveConflict>): List<DriveConflictUiGroup> {
        if (openConflicts.isEmpty()) return emptyList()
        val booksByUrl = database.bookDao().allOnce().associateBy { it.url }
        // Hide conflicts whose book does not exist locally yet (held records and hidden annotations).
        val visibleConflicts = openConflicts.filter { conflict ->
            val url = conflict.bookUrl ?: return@filter false
            booksByUrl[url] != null
        }
        if (visibleConflicts.isEmpty()) return emptyList()
        val result = mutableListOf<DriveConflictUiGroup>()

        val seriesConflicts = visibleConflicts.filter { it.domain == DriveWireCodec.DOMAIN_SERIES }
        val nonSeriesConflicts = visibleConflicts.filter { it.domain != DriveWireCodec.DOMAIN_SERIES }

        // 1. Per-record conflicts for annotation, evaluation, and metadata.
        for (conflict in nonSeriesConflicts) {
            val book = conflict.bookUrl?.let { booksByUrl[it] }
            val bookTitle = book?.title ?: conflict.recordKey
            when (conflict.domain) {
                DriveWireCodec.DOMAIN_ANNOTATION -> {
                    val localAnn = database.annotationDao().byId(conflict.recordKey)
                    val remoteAnn = conflict.remoteJson?.let { raw ->
                        runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
                    }
                    val localText = localAnn?.let { ann ->
                        listOfNotNull(
                            ann.note?.takeIf { it.isNotBlank() },
                            ann.text?.takeIf { it.isNotBlank() }?.let { "“$it”" },
                            ann.chapter?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { ann.kind }
                    } ?: ""
                    val remoteText = remoteAnn?.let { ann ->
                        listOfNotNull(
                            ann.note?.takeIf { it.isNotBlank() },
                            ann.text?.takeIf { it.isNotBlank() }?.let { "“$it”" },
                            ann.chapter?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { ann.kind }
                    } ?: ""
                    result.add(
                        DriveConflictUiGroup(
                            groupId = conflict.conflictId,
                            domain = conflict.domain,
                            kind = conflict.kind,
                            bookTitle = bookTitle,
                            fieldLabel = localAnn?.kind ?: remoteAnn?.kind,
                            localSummary = localText,
                            remoteSummary = remoteText,
                            localDeleted = localAnn == null,
                            remoteDeleted = remoteAnn == null,
                            conflicts = listOf(conflict),
                        ),
                    )
                }
                DriveWireCodec.DOMAIN_EVALUATION -> {
                    val field = DriveEvaluationSync.parseRecordKey(conflict.recordKey)?.second ?: ""
                    val localEval = conflict.bookUrl?.let { database.bookEvaluationDao().get(it, field) }
                    val remoteEval = conflict.remoteJson?.let { raw ->
                        runCatching { DriveWireCodec.decodeEvaluationBody(JSONObject(raw)) }.getOrNull()
                    }
                    val formatVal: (String?) -> String = { raw ->
                        when {
                            raw == null -> ""
                            field == PersonalEvaluation.RATING -> {
                                val stars = raw.toIntOrNull()?.let { it / 2.0 }
                                if (stars != null) "${seriesIndexLabel(stars)} / 5" else raw
                            }
                            else -> raw
                        }
                    }
                    result.add(
                        DriveConflictUiGroup(
                            groupId = conflict.conflictId,
                            domain = conflict.domain,
                            kind = conflict.kind,
                            bookTitle = bookTitle,
                            fieldLabel = field,
                            localSummary = formatVal(localEval?.value),
                            remoteSummary = formatVal(remoteEval?.value),
                            localDeleted = localEval?.value == null,
                            remoteDeleted = remoteEval == null,
                            conflicts = listOf(conflict),
                        ),
                    )
                }
                DriveWireCodec.DOMAIN_METADATA -> {
                    val field = DriveMetadataSync.parseRecordKey(conflict.recordKey)?.second ?: ""
                    val remoteBody = conflict.remoteJson?.let { raw ->
                        runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(raw)) }.getOrNull()
                    }
                    val (localSummary, localDeleted) = when (field) {
                        MetadataOverrideFields.TITLE ->
                            (book?.userTitle.orEmpty()) to (book?.titleOverridden != true)
                        MetadataOverrideFields.AUTHOR ->
                            (book?.userAuthor.orEmpty()) to (book?.authorOverridden != true)
                        MetadataOverrideFields.DESCRIPTION ->
                            (book?.userDescription.orEmpty()) to (book?.descriptionOverridden != true)
                        MetadataOverrideFields.COVER ->
                            (if (book?.coverOverridden == true && !book.userCoverPath.isNullOrBlank()) "Custom cover" else "") to
                                (book?.coverOverridden != true || book.userCoverPath.isNullOrBlank())
                        else -> "" to true
                    }
                    val remoteSummary = when (remoteBody) {
                        is DriveMetadataBody.Text -> remoteBody.value
                        is DriveMetadataBody.Cover -> "Custom cover (${remoteBody.width}×${remoteBody.height})"
                        null -> ""
                    }
                    result.add(
                        DriveConflictUiGroup(
                            groupId = conflict.conflictId,
                            domain = conflict.domain,
                            kind = conflict.kind,
                            bookTitle = bookTitle,
                            fieldLabel = field,
                            localSummary = localSummary,
                            remoteSummary = remoteSummary,
                            localDeleted = localDeleted,
                            remoteDeleted = remoteBody == null,
                            conflicts = listOf(conflict),
                        ),
                    )
                }
            }
        }

        // 2. Group `series` conflicts by `seriesKey` into shelf-level choices.
        if (seriesConflicts.isNotEmpty()) {
            val grouped = linkedMapOf<String, MutableList<DriveConflict>>()
            val shelfNames = mutableMapOf<String, String>()
            for (conflict in seriesConflicts) {
                val book = conflict.bookUrl?.let { booksByUrl[it] }
                val remoteBody = conflict.remoteJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }.getOrNull()
                }
                val localSeriesName = if (book?.seriesOverridden == true) {
                    book.userSeriesName
                } else {
                    book?.seriesName ?: book?.catalogSeriesName ?: book?.fileSeriesName
                }
                val localKey = seriesKey(localSeriesName)
                val remoteKey = remoteBody?.seriesKey.orEmpty()
                val groupKey = localKey.ifBlank { remoteKey }.ifBlank { conflict.conflictId }
                grouped.getOrPut(groupKey) { mutableListOf() }.add(conflict)
                if (groupKey !in shelfNames) {
                    val display = localSeriesName?.takeIf { it.isNotBlank() }
                        ?: remoteBody?.userSeriesName?.takeIf { it.isNotBlank() }
                        ?: book?.title
                        ?: conflict.recordKey
                    shelfNames[groupKey] = display
                }
            }
            for ((groupKey, members) in grouped) {
                val localEntries = members.map { c ->
                    val book = c.bookUrl?.let { booksByUrl[it] }
                    val idx = book?.userSeriesIndex ?: book?.seriesIndex
                    val label = seriesIndexLabel(idx)?.let { "#$it " }.orEmpty() + (book?.title ?: c.recordKey)
                    (idx ?: Double.MAX_VALUE) to label
                }.sortedBy { it.first }.map { it.second }
                val remoteEntries = members.map { c ->
                    val book = c.bookUrl?.let { booksByUrl[it] }
                    val remoteBody = c.remoteJson?.let { raw ->
                        runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }.getOrNull()
                    }
                    val idx = remoteBody?.userSeriesIndex
                    val label = if (remoteBody == null) {
                        "(Default) ${book?.title ?: c.recordKey}"
                    } else {
                        seriesIndexLabel(idx)?.let { "#$it " }.orEmpty() + (book?.title ?: c.recordKey)
                    }
                    (idx ?: Double.MAX_VALUE) to label
                }.sortedBy { it.first }.map { it.second }

                val hasDeleteEdit = members.any { it.kind == DriveConflict.KIND_DELETE_EDIT }
                val allLocalDeleted = members.all { c ->
                    val b = c.bookUrl?.let { booksByUrl[it] }
                    b?.seriesOverridden != true && b?.indexOverridden != true
                }
                val allRemoteDeleted = members.all { it.remoteJson == null }
                result.add(
                    DriveConflictUiGroup(
                        groupId = "series:$groupKey",
                        domain = DriveWireCodec.DOMAIN_SERIES,
                        kind = if (hasDeleteEdit) DriveConflict.KIND_DELETE_EDIT else DriveConflict.KIND_EDIT_EDIT,
                        bookTitle = shelfNames[groupKey] ?: groupKey,
                        fieldLabel = DriveWireCodec.DOMAIN_SERIES,
                        localSummary = localEntries.joinToString(", "),
                        remoteSummary = remoteEntries.joinToString(", "),
                        localDeleted = allLocalDeleted,
                        remoteDeleted = allRemoteDeleted,
                        conflicts = members,
                    ),
                )
            }
        }

        return result
    }

    /** Explicitly confirms an ambiguous title/author match and publishes a `workAlias` merge record. */
    suspend fun confirmWorkMatch(match: DrivePendingWorkMatch) {
        if (!driveSync.hasStore()) return
        val active = driveSync.store.accounts().active() ?: return
        val hlc = driveSync.changeEngine.nextHlc(active.permissionId)
        driveSync.workIdentity.confirmMerge(
            permissionId = active.permissionId,
            bookUrl = match.bookUrl,
            keyA = match.localWorkKey,
            keyB = match.candidateWorkKey,
            hlc = hlc,
        )
        runCatching { driveSync.changeEngine.sync() }
    }

    /** Explicitly rejects an ambiguous title/author match and publishes a `workAlias` reject record. */
    suspend fun rejectWorkMatch(match: DrivePendingWorkMatch) {
        if (!driveSync.hasStore()) return
        val active = driveSync.store.accounts().active() ?: return
        val hlc = driveSync.changeEngine.nextHlc(active.permissionId)
        driveSync.workIdentity.rejectMerge(
            permissionId = active.permissionId,
            bookUrl = match.bookUrl,
            keyA = match.localWorkKey,
            keyB = match.candidateWorkKey,
            hlc = hlc,
        )
        runCatching { driveSync.changeEngine.sync() }
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
        return when (val result = authClient.authorizeSilently(accountEmail = active.email.ifBlank { null })) {
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

    /**
     * Counts what the initial Drive export will send, with the export's own rules:
     * library books only (browse-only copies stay out), minus the untouched server
     * previews a switch removes; notes and closed reading sessions recorded on this
     * device for those books (imported and Drive-pulled sessions are never sent).
     */
    internal suspend fun readLocalLibrarySummary(): LocalLibrarySummary {
        val bookDao = database.bookDao()
        val removedOnSwitch = if (remoteAccount.current() != null) {
            bookDao.remoteNotDownloadedUrls().toSet() -
                bookDao.remoteNotDownloadedPreservedStubs().mapTo(HashSet()) { it.url }
        } else {
            emptySet()
        }
        val eligibleUrls = bookDao.allOnce()
            .filter { it.browseServerId == null && it.url !in removedOnSwitch }
            .mapTo(HashSet()) { it.url }
        val notes = database.annotationDao().all().count { it.bookId in eligibleUrls }
        val sessions = database.readingSessionDao().localSessionsForSync().count { it.bookUrl in eligibleUrls }
        return LocalLibrarySummary(
            booksCount = eligibleUrls.size,
            notesCount = notes,
            readingSessionsCount = sessions,
            // Only unowned words: other accounts' vocabulary is not this library's to disclose.
            vocabularyWordsCount = database.vocabularyDao().countForOwner(""),
        )
    }

    private suspend fun handleAuthorizedTokenLocked(accessToken: String) {
        accountGeneration++
        val about = try {
            driveApi.getAbout(accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveAuthExpiredException) {
            authClient.clearCachedToken(accessToken)
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessageRes = R.string.drive_sync_error_token_rejected,
            )
            return
        } catch (_: Exception) {
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessageRes = R.string.drive_sync_error_unreachable,
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
        val connectedServer = remoteAccount.current()?.let(::ServerConnectionStamp)
        val mainServerConnected = connectedServer != null

        val needsConfirmation = isSwitchingFromOtherAccount ||
            mainServerConnected ||
            (!isReconnectingSamePrimary && localSummary.hasLocalData)

        if (needsConfirmation) {
            val previousState = primaryExisting?.state
            // Keep Account A PAUSED (and stop its workers) while awaiting confirmation,
            // preserving all of A's cursors, queues, folder mappings, and conflicts,
            // and remembering that pause so a process death during the confirmation
            // can be undone on the next start.
            if (primaryExisting != null && previousState != DriveAccount.STATE_PAUSED) {
                driveSync.pauseForPendingSwitch(primaryExisting)
            }
            pendingAccessToken = accessToken
            ephemeral.value = EphemeralState(
                busy = false,
                pendingConfirmation = PendingAccountSwitchConfirmation(
                    previousAccount = primaryExisting,
                    previousState = previousState,
                    targetPermissionId = about.permissionId,
                    targetEmail = about.emailAddress,
                    localSummary = localSummary,
                    mainServerConnected = mainServerConnected,
                    confirmedServer = connectedServer,
                ),
            )
            return
        }

        try {
            driveSync.activateAccount(
                permissionId = about.permissionId,
                email = about.emailAddress,
                now = clock(),
                confirmedDisconnectServer = false,
            )
            pendingAccessToken = null
            ephemeral.value = EphemeralState()
            driveSync.notifyActivated()
            presentInitialUploadSelection()
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
                    targetEmail = about.emailAddress,
                    localSummary = localSummary,
                    mainServerConnected = true,
                    confirmedServer = e.server,
                ),
            )
        } catch (_: Exception) {
            ephemeral.value = ephemeral.value.copy(
                busy = false,
                errorMessageRes = R.string.drive_sync_error_activate_failed,
            )
        }
    }

    companion object {
        private const val TAG = "DriveAuth"
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
