package com.chmouel.liseur.providers

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository.Companion.MAX_COVER_SOURCE_BYTES
import com.chmouel.liseur.data.library.FieldEdit
import com.chmouel.liseur.data.library.fileBookInSeries
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.UnreadableCredentialException
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.domain.resolveMetadata
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.validSeriesPosition
import com.chmouel.liseur.hardcover.BookGeneration
import com.chmouel.liseur.hardcover.CoverSimilarity
import com.chmouel.liseur.hardcover.DetailField
import com.chmouel.liseur.hardcover.DetailProposal
import com.chmouel.liseur.hardcover.EditionChoice
import com.chmouel.liseur.hardcover.HardcoverAccount
import com.chmouel.liseur.hardcover.HardcoverBookLink
import com.chmouel.liseur.hardcover.HardcoverClient
import com.chmouel.liseur.hardcover.HardcoverConflictResolution
import com.chmouel.liseur.hardcover.HardcoverConnectOutcome
import com.chmouel.liseur.hardcover.HardcoverDatabase
import com.chmouel.liseur.hardcover.HardcoverEdition
import com.chmouel.liseur.hardcover.HardcoverEvaluation
import com.chmouel.liseur.hardcover.HardcoverEvaluationPlan
import com.chmouel.liseur.hardcover.HardcoverImages
import com.chmouel.liseur.hardcover.HardcoverPlan
import com.chmouel.liseur.hardcover.HardcoverPushOutcome
import com.chmouel.liseur.hardcover.HardcoverPushWorker
import com.chmouel.liseur.hardcover.HardcoverRepository
import com.chmouel.liseur.hardcover.LocalDetails
import com.chmouel.liseur.hardcover.LocalReading
import com.chmouel.liseur.hardcover.proposeUpdates
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.settings.SwitchRow
import com.chmouel.liseur.ui.settings.search.SettingsFocus
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import com.chmouel.liseur.ui.windowWidth
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Play build reports reading to Hardcover (ADR-0075).
 *
 * Hardcover is one fixed service the reader cannot point elsewhere, so it
 * stays out of the F-Droid build, where the same class does nothing. Its
 * account and matches live in their own database, apart from the library.
 */
internal class ReadingTracker(context: Context, private val database: LiseurDatabase) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { HardcoverDatabase.open(this.context) }

    val hardcover: HardcoverRepository by lazy {
        HardcoverRepository(
            dao = store.hardcover(),
            bookFor = { database.bookDao().getByUrl(it) },
            localFor = ::localReading,
            writeLocalEvaluation = ::writeLocalEvaluation,
            inTransaction = { work -> store.withTransaction { work() } },
        )
    }

    private val connecting = MutableStateFlow(false)
    private val connectFailure = MutableStateFlow<SyncFailure?>(null)

    /** Sends books still queued from before the process stopped, which no worker may be waiting on. */
    fun resume() {
        if (!used()) return
        scope.launch {
            runCatching {
                hardcover.settleForgetting()
                if (hardcover.nextPending() != null) HardcoverPushWorker.enqueue(context)
            }
        }
    }

    /** A book was closed, given a status, or rated: queue it when automatic reports are on. */
    fun bookChanged(bookUrl: String) {
        if (!used()) return
        scope.launch {
            runCatching { if (hardcover.queue(bookUrl)) HardcoverPushWorker.enqueue(context) }
        }
    }

    /** Holds pushes for books a library transaction is removing. */
    suspend fun forgettingBooks(bookUrls: List<String>) {
        if (bookUrls.isNotEmpty() && used()) hardcover.willForget(bookUrls)
    }

    /** Drops what Hardcover held for books whose removal committed, or lets pushes resume. */
    fun forgotBooks(bookUrls: List<String>, committed: Boolean) {
        if (bookUrls.isEmpty() || !used()) return
        scope.launch {
            runCatching {
                if (committed) {
                    hardcover.forgetBooks(bookUrls)
                } else {
                    hardcover.keep(bookUrls)
                    // A worker may have dropped their queued push while they were held.
                    bookUrls.forEach { if (hardcover.queue(it)) HardcoverPushWorker.enqueue(context) }
                }
            }
        }
    }

    /** Whether the book has a Hardcover edition or is kept off Hardcover. */
    suspend fun keeps(bookUrl: String): Boolean = used() && hardcover.remembers(bookUrl)

    /** The account under `hardcover`, or null when there is none to keep. */
    suspend fun backup(into: JSONObject) {
        val account = if (used()) hardcover.current() else null
        into.put(
            BACKUP_KEY,
            account?.let {
                JSONObject()
                    .put("userId", it.userId)
                    .put("username", it.username)
                    .put("token", it.token ?: throw UnreadableCredentialException())
                    .put("autoSync", it.autoSync)
            } ?: JSONObject.NULL,
        )
    }

    /**
     * Seals a backed-up token before anything is replaced. A backup without
     * the key, as from the F-Droid build, leaves the account here alone.
     */
    fun prepareRestore(payload: JSONObject): suspend () -> Unit {
        if (!payload.has(BACKUP_KEY)) return {}
        val account = payload.optJSONObject(BACKUP_KEY)?.let {
            val token = HardcoverClient.bearer(it.getString("token"))
            require(token.isNotEmpty() && it.getString("username").isNotBlank())
            HardcoverAccount(
                userId = it.getLong("userId"),
                username = it.getString("username"),
                tokenCipher = HardcoverAccount.seal(token),
                addedAt = 0,
                autoSync = it.optBoolean("autoSync", true),
            )
        }
        return {
            HardcoverPushWorker.cancel(context)
            hardcover.restoreBackup(account)
        }
    }

    /** Whether Hardcover was ever set up here; until then nothing opens its database. */
    private fun used(): Boolean = context.getDatabasePath(HardcoverDatabase.NAME).exists()

    private fun connect(token: String) {
        if (connecting.value) return
        connecting.value = true
        connectFailure.value = null
        scope.launch {
            (hardcover.connect(token) as? HardcoverConnectOutcome.Failed)?.let { connectFailure.value = it.reason }
            connecting.value = false
        }
    }

    private fun disconnect() {
        connectFailure.value = null
        scope.launch {
            HardcoverPushWorker.cancel(context)
            hardcover.disconnect()
        }
    }

    private suspend fun localReading(bookUrl: String): LocalReading? {
        val book = database.bookDao().getByUrl(bookUrl) ?: return null
        val progress = database.readingProgressDao().get(bookUrl)
        val override = progress?.override ?: FinishedOverride.NONE
        val status = readingStatusFor(progress?.totalProgression, override, progress?.partialCoverage == true)
        val rating = database.bookEvaluationDao().get(bookUrl, PersonalEvaluation.RATING)?.value
        val review = database.bookEvaluationDao().get(bookUrl, PersonalEvaluation.REVIEW)?.value
        return LocalReading(
            status = status,
            chosen = override != FinishedOverride.NONE && override != FinishedOverride.UNREAD,
            progression = progress?.totalProgression,
            startedOn = database.readingSessionDao().startedAtFor(bookUrl).minOrNull()?.let(::day),
            finishedOn = book.finishedAt?.let(::day),
            rating = HardcoverPlan.ratingFromLocal(rating),
            ratingRaw = rating,
            review = review,
        )
    }

    private suspend fun writeLocalEvaluation(
        bookUrl: String,
        field: String,
        expected: String?,
        value: String?,
        generation: BookGeneration,
    ): Boolean {
        var changed = false
        val written = database.withTransaction {
            val book = database.bookDao().getByUrl(bookUrl) ?: return@withTransaction false
            // A file replaced at the same URL while Hardcover answered is another book.
            if (BookGeneration(book) != generation) return@withTransaction false
            val current = database.bookEvaluationDao().get(bookUrl, field)?.value
            if (current != expected) return@withTransaction false
            if (current == value) return@withTransaction true
            changed = true
            database.bookEvaluationDao().edit(
                bookUrl = bookUrl,
                field = field,
                value = value,
                title = book.title,
                author = book.author.orEmpty(),
                now = System.currentTimeMillis(),
            )
            true
        }
        if (changed) context.container.requestBookSync(bookUrl)
        return written
    }

    private fun day(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private suspend fun localDetails(bookUrl: String): Pair<Book, LocalDetails>? {
        val book = database.bookDao().getByUrl(bookUrl) ?: return null
        val resolved = database.bookMetadataDao().forBook(bookUrl)
            .mapNotNull { row -> MetadataSource.of(row.source)?.let { it to row.toSnapshot() } }
            .toMap()
            .let(::resolveMetadata)
        val description = if (book.descriptionOverridden) {
            book.userDescription?.takeIf { it.isNotEmpty() }
        } else {
            resolved?.description
        }
        val link = hardcover.currentLink(bookUrl)
        val details = LocalDetails(
            title = book.title,
            author = book.author,
            description = description,
            seriesName = book.seriesName,
            seriesIndex = book.seriesIndex,
            importedCoverUrl = link?.importedCoverUrl?.takeIf {
                book.coverOverridden && book.userCoverSha256 != null && book.userCoverSha256 == link.importedCoverSha256
            },
            language = resolved?.languages?.firstOrNull(),
        )
        return book to details
    }

    /**
     * Saves the ticked proposals, each only while the book is still the
     * [reviewed] generation, so a file replaced meanwhile keeps its own details.
     */
    private suspend fun applyDetailsUpdate(
        reviewed: Book,
        proposals: List<DetailProposal>,
        selected: Set<DetailField>,
        coverBytes: ByteArray?,
    ): Int? {
        val bookUrl = reviewed.url
        val byField = proposals.filter { it.field in selected }.associateBy { it.field }
        if (byField.isEmpty()) return null
        val appContainer = context.container
        val stillReviewed: suspend () -> Boolean = {
            database.bookDao().getByUrl(bookUrl)?.sameGeneration(reviewed) == true
        }
        var applied = false
        val titleEdit = byField[DetailField.TITLE]?.newText?.let(FieldEdit::Set) ?: FieldEdit.Unchanged
        val authorEdit = byField[DetailField.AUTHOR]?.newText?.let(FieldEdit::Set) ?: FieldEdit.Unchanged
        val descriptionEdit = byField[DetailField.DESCRIPTION]?.newText?.let(FieldEdit::Set) ?: FieldEdit.Unchanged
        if (titleEdit is FieldEdit.Set || authorEdit is FieldEdit.Set || descriptionEdit is FieldEdit.Set) {
            val current = appContainer.metadataOverrides.applyTextEdits(
                bookUrl = bookUrl,
                title = titleEdit,
                author = authorEdit,
                description = descriptionEdit,
                expected = reviewed,
            )
            if (!current) return R.string.hardcover_update_details_stale
            applied = true
        }
        byField[DetailField.SERIES]?.let { series ->
            val filed = fileBookInSeries(
                bookDao = database.bookDao(),
                catalog = appContainer.remoteCatalog,
                book = reviewed,
                name = series.newText,
                index = series.newSeriesIndex,
                expected = reviewed,
                inTransaction = { work -> database.withTransaction { work() } },
            )
            if (!filed) return R.string.hardcover_update_details_stale
            applied = true
        }
        byField[DetailField.COVER]?.newCoverUrl?.let { url ->
            val sha256 = coverBytes?.let { bytes ->
                appContainer.metadataOverrides.coverFromBytes(bookUrl, bytes, expected = reviewed)
            }
            if (sha256 != null) {
                hardcover.rememberImportedCover(bookUrl, url, sha256)
            } else {
                if (!stillReviewed()) return R.string.hardcover_update_details_stale
                return if (applied) {
                    R.string.hardcover_update_details_cover_failed
                } else {
                    R.string.hardcover_update_details_cover_only_failed
                }
            }
        }
        return R.string.hardcover_update_details_done
    }

    /**
     * Hardcover's cover, downloaded once for both the preview and the save,
     * or null when it cannot be had or looks like the cover the book has.
     */
    private suspend fun newCover(url: String, book: Book): CoverFetch = withContext(Dispatchers.IO) {
        val bytes = HardcoverImages.downloadCover(url) ?: return@withContext CoverFetch.Failed
        val theirs = HardcoverImages.coverHash(bytes) ?: return@withContext CoverFetch.Failed
        val current = book.effectiveCoverPath?.let(::File)?.takeIf { it.isFile && it.length() <= MAX_COVER_SOURCE_BYTES }
        val ours = current?.let { runCatching { HardcoverImages.coverHash(it.readBytes()) }.getOrNull() }
        if (ours != null && CoverSimilarity.looksSame(ours, theirs)) CoverFetch.Unchanged else CoverFetch.Downloaded(bytes)
    }

    /**
     * The Library row that leads to the reading services, on the Services
     * page where every account lives.
     */
    @Composable
    fun SettingsEntry(onOpen: () -> Unit) {
        val account by remember { if (used()) hardcover.account else flowOf(null) }.collectAsState(initial = null)
        RowDivider()
        ConnectionRow(
            icon = { Icon(Icons.Outlined.TaskAlt, contentDescription = null) },
            title = stringResource(R.string.reading_services_title),
            subtitle = account?.let { stringResource(R.string.hardcover_connected_as, it.username) }
                ?: stringResource(R.string.hardcover_settings_summary),
            onClick = {
                SettingsFocus.key = PlaySettingsEntries.Hardcover.key
                onOpen()
            },
            modifier = Modifier.settingsAnchor(PlaySettingsEntries.ReadingServices),
        )
    }

    /** The reading services group on the Services page. */
    @Composable
    fun Rows() {
        val account by hardcover.account.collectAsState(initial = null)
        val busy by connecting.collectAsState()
        val failure by connectFailure.collectAsState()
        SettingsGroup(stringResource(R.string.reading_services_title)) {
            Column(Modifier.settingsAnchor(PlaySettingsEntries.Hardcover)) {
                Text(
                    text = stringResource(R.string.hardcover_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                Text(
                    text = stringResource(R.string.hardcover_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.hardcover_intro_evaluations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                )
                val current = account
                if (current != null) {
                    RowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.hardcover_connected_as, current.username)) },
                        supportingContent = if (current.tokenRefused) {
                            { Text(stringResource(R.string.hardcover_token_refused), color = MaterialTheme.colorScheme.error) }
                        } else {
                            null
                        },
                        trailingContent = {
                            TextButton(onClick = ::disconnect) { Text(stringResource(R.string.hardcover_disconnect)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.hardcover_auto_sync),
                        subtitle = stringResource(R.string.hardcover_auto_sync_detail),
                        checked = current.autoSync,
                        onCheckedChange = { on -> scope.launch { hardcover.setAutoSync(on) } },
                    )
                }
                if (current == null || current.tokenRefused) {
                    RowDivider()
                    TokenField(busy = busy, failure = failure)
                }
            }
        }
    }

    @Composable
    private fun TokenField(busy: Boolean, failure: SyncFailure?) {
        val uriHandler = LocalUriHandler.current
        val clipboard = LocalClipboard.current
        val context = LocalContext.current
        val pasteScope = rememberCoroutineScope()
        // Plain remember on purpose: a token half pasted must not outlive the screen in saved state.
        var token by remember { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.hardcover_token_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(HardcoverClient.TOKEN_PAGE) } },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.hardcover_token_get))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = stringResource(R.string.hardcover_token_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                enabled = !busy,
                isError = failure != null,
                placeholder = { Text(stringResource(R.string.hardcover_token_hint), maxLines = 1) },
                trailingIcon = if (token.isEmpty() && !busy) {
                    {
                        IconButton(
                            onClick = {
                                // Read only on this tap: the clipboard is the reader's, not ours to watch.
                                pasteScope.launch {
                                    val text = clipboard.getClipEntry()?.clipData
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                    if (!text.isNullOrEmpty()) token = text
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = stringResource(R.string.read_aloud_settings_key_paste),
                            )
                        }
                    }
                } else {
                    null
                },
                supportingText = failure?.let { { Text(stringResource(failureMessage(it))) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) connect(token) }),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.hardcover_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (busy) BusyIndicator()
                Button(onClick = { connect(token) }, enabled = !busy && token.isNotBlank()) {
                    Text(stringResource(R.string.hardcover_connect))
                }
            }
        }
    }

    /** The review editor's note while this book's review is published on Hardcover, else null. */
    @Composable
    fun reviewPublicNote(bookUrl: String): String? {
        if (!remember { used() }) return null
        val account by hardcover.account.collectAsState(initial = null)
        val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsState(initial = null)
        // An unmatched book can still be matched and reported automatically, so only an exclusion keeps it off.
        if (account == null || link?.excluded == true) return null
        return stringResource(R.string.hardcover_review_public_note)
    }

    /** One book's standing with Hardcover, on its details page. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun BookDetailsSection(
        bookUrl: String,
        modifier: Modifier,
        onOpenSettings: () -> Unit = {},
    ) {
        if (!remember { used() }) return
        val account by hardcover.account.collectAsState(initial = null)
        // Opening the Services page creates the database, so only a past connection counts as set up.
        val everConnected by produceState(false, account) { value = account != null || hardcover.everConnected() }
        if (!everConnected) return
        val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsState(initial = null)
        val evaluations by remember(bookUrl) { hardcover.evaluations(bookUrl) }.collectAsState(initial = emptyList())
        val conflicts = remember(evaluations) { evaluations.filter { it.conflict } }
        val ui = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember(bookUrl) { mutableStateOf<HardcoverNotice?>(null) }
        var choices by remember(bookUrl) { mutableStateOf<PickerSession?>(null) }
        var review by remember(bookUrl) { mutableStateOf<DetailsReviewSession?>(null) }
        var confirmingUnlink by remember(bookUrl) { mutableStateOf(false) }
        val view = hardcoverBookDetailsView(link, account)
        var autoChecked by remember(bookUrl) { mutableStateOf(false) }
        LaunchedEffect(bookUrl, account?.userId, account?.autoSync, link?.matched, link?.excluded) {
            if (autoChecked) return@LaunchedEffect
            val currentAccount = account ?: return@LaunchedEffect
            val currentLink = link ?: return@LaunchedEffect
            if (!currentAccount.autoSync || !currentLink.matched || currentLink.excluded) return@LaunchedEffect
            autoChecked = true
            scope.launch { runCatching { hardcover.checkFromDetails(bookUrl) } }
        }
        fun busyWhile(work: suspend () -> Unit) {
            busy = true
            message = null
            ui.launch {
                work()
                busy = false
            }
        }
        fun resolveConflict(field: String, keepLocal: Boolean) {
            busyWhile {
                message = runCatching {
                    val outcome = scope.async {
                        if (keepLocal) {
                            hardcover.resolveConflictKeepLocal(bookUrl, field)
                        } else {
                            hardcover.resolveConflictUseRemote(bookUrl, field)
                        }
                    }.await()
                    HardcoverNotice(resolutionMessage(outcome), isError = outcome != HardcoverConflictResolution.Resolved)
                }.getOrElse { HardcoverNotice(R.string.hardcover_error_other, isError = true) }
            }
        }
        fun openChoices(push: Boolean) {
            busyWhile {
                when (val found = hardcover.candidates(bookUrl)) {
                    is RemoteResult.Ok -> choices = PickerSession(found.value, push)
                    is RemoteResult.Failed -> message = HardcoverNotice(failureMessage(found.reason), isError = true)
                }
            }
        }
        fun openDetailsUpdate() {
            busyWhile {
                val (book, local) = localDetails(bookUrl) ?: return@busyWhile
                when (val fetched = hardcover.editionDetails(bookUrl)) {
                    is RemoteResult.Failed -> message = HardcoverNotice(failureMessage(fetched.reason), isError = true)
                    is RemoteResult.Ok -> {
                        val remote = fetched.value
                        if (remote == null) {
                            message = HardcoverNotice(R.string.hardcover_error_other, isError = true)
                            return@busyWhile
                        }
                        var proposals = proposeUpdates(local, remote)
                        val cover = proposals.firstOrNull { it.field == DetailField.COVER }?.newCoverUrl
                            ?.let { url -> newCover(url, book) }
                        val coverBytes = (cover as? CoverFetch.Downloaded)?.bytes
                        if (coverBytes == null) proposals = proposals.filter { it.field != DetailField.COVER }
                        if (cover == CoverFetch.Failed) {
                            message = HardcoverNotice(R.string.hardcover_update_details_cover_only_failed, isError = true)
                        }
                        if (proposals.isEmpty()) {
                            if (cover != CoverFetch.Failed) {
                                message = HardcoverNotice(R.string.hardcover_update_details_empty, isError = false)
                            }
                        } else {
                            review = DetailsReviewSession(book = book, proposals = proposals, coverBytes = coverBytes)
                        }
                    }
                }
            }
        }
        fun performAction(action: HardcoverAction) {
            when (action) {
                HardcoverAction.CHOOSE_EDITION -> openChoices(push = false)
                HardcoverAction.CHANGE_EDITION -> openChoices(push = false)
                HardcoverAction.CHOOSE_AND_PUSH -> openChoices(push = true)
                HardcoverAction.KEEP_OFF -> ui.launch { hardcover.setExcluded(bookUrl, true) }
                HardcoverAction.PUSH -> {
                    busyWhile {
                        when (val outcome = hardcover.push(bookUrl, asked = true)) {
                            // A match this device lost is still worth asking for.
                            HardcoverPushOutcome.NeedsMatch -> when (val found = hardcover.candidates(bookUrl)) {
                                is RemoteResult.Ok -> choices = PickerSession(found.value, push = true)
                                is RemoteResult.Failed -> message = HardcoverNotice(failureMessage(found.reason), isError = true)
                            }
                            else -> message = outcomeNotice(outcome)
                        }
                    }
                }
                HardcoverAction.UPDATE_DETAILS -> openDetailsUpdate()
                HardcoverAction.UNLINK -> confirmingUnlink = true
                HardcoverAction.OPEN_SETTINGS -> {
                    SettingsFocus.key = PlaySettingsEntries.Hardcover.key
                    onOpenSettings()
                }
            }
        }

        HardcoverBookPanel(
            view = view,
            busy = busy,
            message = message,
            onToggleSync = { on -> ui.launch { hardcover.setExcluded(bookUrl, !on) } },
            onAction = ::performAction,
            modifier = modifier.padding(top = 20.dp),
        )
        if (conflicts.isNotEmpty() && view.showSyncSwitch && view.syncSwitchChecked == true) {
            EvaluationConflictsCard(
                conflicts = conflicts,
                busy = busy,
                onKeepLocal = { field -> resolveConflict(field, keepLocal = true) },
                onUseRemote = { field -> resolveConflict(field, keepLocal = false) },
                modifier = modifier.padding(top = 12.dp),
            )
        }

        if (confirmingUnlink) {
            AlertDialog(
                onDismissRequest = { confirmingUnlink = false },
                title = { Text(stringResource(R.string.hardcover_book_unlink_title)) },
                text = { Text(stringResource(R.string.hardcover_book_unlink_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingUnlink = false
                            busyWhile {
                                // Long-lived scope: the unlink finishes even if Book details closes meanwhile.
                                val done = runCatching { scope.async { hardcover.unlink(bookUrl) }.await() }.isSuccess
                                if (!done) message = HardcoverNotice(R.string.hardcover_error_other, isError = true)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.hardcover_book_unlink))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingUnlink = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        choices?.let { session ->
            EditionPicker(
                session = session,
                bookUrl = bookUrl,
                currentEditionId = link?.editionId,
                onChoose = { edition ->
                    val push = session.push
                    choices = null
                    busyWhile {
                        when (
                            val chosen = hardcover.chooseThenPush(
                                candidates = session.context,
                                bookUrl = bookUrl,
                                edition = edition,
                                push = push,
                                asked = push,
                            )
                        ) {
                            EditionChoice.Chosen -> bookChanged(bookUrl)
                            else -> message = choiceNotice(chosen)
                        }
                    }
                },
                onDismiss = { choices = null },
            )
        }

        review?.let { session ->
            DetailsUpdateSheet(
                session = session,
                onApply = { selected ->
                    val proposals = session.proposals
                    review = null
                    busyWhile {
                        // The tracker's scope finishes the save even if Book details closes meanwhile.
                        val outcomeRes = runCatching {
                            scope.async { applyDetailsUpdate(session.book, proposals, selected, session.coverBytes) }.await()
                        }.getOrElse { R.string.hardcover_error_other }
                        message = outcomeRes?.let { res ->
                            HardcoverNotice(
                                textRes = res,
                                isError = res != R.string.hardcover_update_details_done,
                            )
                        }
                    }
                },
                onDismiss = { review = null },
            )
        }
    }

    /** Action to push the current book's place to Hardcover, or null when no account is connected. */
    @Composable
    fun rememberReaderPushAction(
        bookUrl: String,
        savePlace: suspend () -> Boolean,
        requestEdition: ((bookUrl: String, push: Boolean) -> Unit)? = null,
    ): ((savePlaceFirst: Boolean) -> Unit)? {
        if (!remember { used() }) return null
        val account by hardcover.account.collectAsState(initial = null)
        if (account == null) return null
        val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsState(initial = null)
        return { savePlaceFirst ->
            if (requestEdition != null && opensEditionPicker(link?.matched == true, link?.excluded == true)) {
                requestEdition(bookUrl, true)
            } else {
                scope.launch {
                    if (savePlaceFirst) {
                        // The place on screen can be ahead of the stored one until it is saved.
                        val saved = withContext(Dispatchers.Main.immediate) { savePlace() }
                        if (!saved) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.reader_position_not_saved, Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                    when (val outcome = hardcover.push(bookUrl, asked = true)) {
                        HardcoverPushOutcome.NeedsMatch if requestEdition != null -> {
                            withContext(Dispatchers.Main) { requestEdition(bookUrl, true) }
                        }
                        else -> {
                            val text = outcomeMessage(outcome)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ReaderEditionPicker(
        ask: String?,
        pushes: Boolean,
        onDone: () -> Unit,
        savePlace: suspend () -> Boolean,
    ) {
        if (ask == null || !remember { used() }) return
        val link by remember(ask) { hardcover.link(ask) }.collectAsState(initial = null)
        var loadedFor by remember { mutableStateOf<String?>(null) }
        var loaded by remember { mutableStateOf<PickerSession?>(null) }
        LaunchedEffect(ask) {
            if (loadedFor == ask) return@LaunchedEffect
            loaded = null
            when (val found = hardcover.candidates(ask)) {
                is RemoteResult.Ok -> {
                    loadedFor = ask
                    loaded = PickerSession(found.value, pushes)
                }
                is RemoteResult.Failed -> {
                    onDone()
                    Toast.makeText(context, failureMessage(found.reason), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (loadedFor != ask) {
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(stringResource(R.string.hardcover_book_choose)) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) { BusyIndicator() }
                },
                confirmButton = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                },
            )
            return
        }
        val session = loaded ?: return
        EditionPicker(
            session = session,
            bookUrl = ask,
            currentEditionId = link?.editionId,
            onChoose = { edition ->
                onDone()
                scope.launch {
                    if (pushes) {
                        val saved = withContext(Dispatchers.Main.immediate) { savePlace() }
                        if (!saved) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.reader_position_not_saved, Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                    val chosen = hardcover.chooseThenPush(
                        candidates = session.context,
                        bookUrl = ask,
                        edition = edition,
                        push = pushes,
                        asked = pushes,
                    )
                    if (chosen == EditionChoice.Chosen && !pushes) {
                        bookChanged(ask)
                        return@launch
                    }
                    choiceMessage(chosen)?.let { text ->
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = onDone,
        )
    }

    @Composable
    private fun EditionPicker(
        session: PickerSession,
        bookUrl: String,
        currentEditionId: Long?,
        onChoose: (HardcoverEdition) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val book by produceState<Book?>(null, bookUrl) { value = database.bookDao().getByUrl(bookUrl) }
        EditionPickerPage(
            session = session,
            bookLabel = book?.let { listOfNotNull(it.title, it.author).joinToString(" · ") },
            currentEditionId = currentEditionId,
            lookup = hardcover::lookup,
            failureMessage = ::failureMessage,
            onChoose = onChoose,
            onDismiss = onDismiss,
        )
    }

    private companion object {
        const val BACKUP_KEY = "hardcover"

        @StringRes
        fun failureMessage(reason: SyncFailure): Int = when (reason) {
            SyncFailure.Unauthorised -> R.string.hardcover_error_token
            SyncFailure.Forbidden -> R.string.hardcover_error_scope
            SyncFailure.Offline, SyncFailure.Timeout -> R.string.hardcover_error_offline
            else -> R.string.hardcover_error_other
        }

        @StringRes
        fun outcomeMessage(outcome: HardcoverPushOutcome): Int = outcomeNotice(outcome).textRes

        fun outcomeNotice(outcome: HardcoverPushOutcome): HardcoverNotice = when (outcome) {
            HardcoverPushOutcome.Pushed -> HardcoverNotice(R.string.hardcover_pushed, isError = false)
            HardcoverPushOutcome.UpToDate -> HardcoverNotice(R.string.hardcover_up_to_date, isError = false)
            HardcoverPushOutcome.NeedsMatch -> HardcoverNotice(R.string.hardcover_needs_match, isError = false)
            HardcoverPushOutcome.SwitchedOff -> HardcoverNotice(R.string.hardcover_push_off, isError = false)
            HardcoverPushOutcome.NotConnected -> HardcoverNotice(R.string.hardcover_error_token, isError = true)
            is HardcoverPushOutcome.Failed -> HardcoverNotice(failureMessage(outcome.reason), isError = true)
        }

        @StringRes
        fun resolutionMessage(outcome: HardcoverConflictResolution): Int = when (outcome) {
            HardcoverConflictResolution.Resolved -> R.string.hardcover_conflict_resolved
            HardcoverConflictResolution.Stale -> R.string.hardcover_conflict_stale
            HardcoverConflictResolution.NeedsMatch -> R.string.hardcover_needs_match
            HardcoverConflictResolution.SwitchedOff -> R.string.hardcover_push_off
            HardcoverConflictResolution.NotConnected -> R.string.hardcover_error_token
            is HardcoverConflictResolution.Failed -> failureMessage(outcome.reason)
        }

        @StringRes
        /** Nothing to say for a choice made only, which the caller follows up itself. */
        fun choiceMessage(choice: EditionChoice): Int? = choiceNotice(choice)?.textRes

        fun choiceNotice(choice: EditionChoice): HardcoverNotice? = when (choice) {
            EditionChoice.Chosen -> null
            is EditionChoice.Pushed -> outcomeNotice(choice.outcome)
            EditionChoice.SwitchedOff -> HardcoverNotice(R.string.hardcover_push_off, isError = false)
            EditionChoice.NotConnected, EditionChoice.OtherAccount ->
                HardcoverNotice(R.string.hardcover_error_token, isError = true)
            EditionChoice.AlreadyGone -> null
            is EditionChoice.Failed -> HardcoverNotice(failureMessage(choice.reason), isError = true)
        }
    }
}

internal data class HardcoverNotice(
    @StringRes val textRes: Int,
    val isError: Boolean,
)

/** An unmatched book opens the picker; one kept off Hardcover goes to push, which says so. */
internal fun opensEditionPicker(matched: Boolean, excluded: Boolean): Boolean = !matched && !excluded

internal enum class HardcoverAction(@StringRes val labelRes: Int) {
    CHOOSE_EDITION(R.string.hardcover_book_choose),
    CHANGE_EDITION(R.string.hardcover_book_change),
    CHOOSE_AND_PUSH(R.string.hardcover_book_choose_and_push),
    PUSH(R.string.hardcover_push),
    UPDATE_DETAILS(R.string.hardcover_update_details),
    KEEP_OFF(R.string.hardcover_book_keep_off),
    UNLINK(R.string.hardcover_book_unlink),
    OPEN_SETTINGS(R.string.hardcover_book_open_settings),
}

internal enum class HardcoverStatusChip(@StringRes val labelRes: Int, val linked: Boolean) {
    LINKED(R.string.hardcover_book_linked, linked = true),
    NOT_LINKED(R.string.hardcover_book_not_linked, linked = false),
}

internal data class HardcoverBookDetailsView(
    @StringRes val subtitleRes: Int,
    val matchedEditionTitle: String?,
    val statusChip: HardcoverStatusChip?,
    val showSyncSwitch: Boolean,
    val syncSwitchChecked: Boolean?,
    val primaryAction: HardcoverAction?,
    val primaryActionEnabled: Boolean = true,
    val secondaryActions: List<HardcoverAction>,
    val showUpdateDetails: Boolean = false,
)

/** An edition Hardcover gave no title says only that it is linked. */
private fun matchedSubtitle(title: String?): Int =
    if (title.isNullOrBlank()) R.string.hardcover_book_linked else R.string.hardcover_book_matched

internal fun hardcoverBookDetailsView(
    link: HardcoverBookLink?,
    account: HardcoverAccount? = HardcoverAccount(
        userId = 1L,
        username = "",
        tokenCipher = "",
        addedAt = 0L,
    ),
): HardcoverBookDetailsView {
    if (account == null || account.tokenRefused) {
        return HardcoverBookDetailsView(
            subtitleRes = if (account?.tokenRefused == true) {
                R.string.hardcover_book_needs_new_token
            } else {
                R.string.hardcover_book_not_connected
            },
            matchedEditionTitle = null,
            statusChip = null,
            showSyncSwitch = false,
            syncSwitchChecked = null,
            primaryAction = null,
            primaryActionEnabled = false,
            secondaryActions = listOf(HardcoverAction.OPEN_SETTINGS),
            showUpdateDetails = false,
        )
    }
    val matched = link?.matched == true
    val excluded = link?.excluded == true
    return when {
        !matched && !excluded -> HardcoverBookDetailsView(
            subtitleRes = R.string.hardcover_book_unmatched,
            matchedEditionTitle = null,
            statusChip = HardcoverStatusChip.NOT_LINKED,
            showSyncSwitch = false,
            syncSwitchChecked = null,
            primaryAction = HardcoverAction.CHOOSE_EDITION,
            primaryActionEnabled = true,
            // Auto-sync can still match and report an unlinked book, so it needs a way out.
            secondaryActions = listOf(HardcoverAction.CHOOSE_AND_PUSH, HardcoverAction.KEEP_OFF),
            showUpdateDetails = false,
        )
        !matched && excluded -> HardcoverBookDetailsView(
            subtitleRes = R.string.hardcover_book_unmatched,
            matchedEditionTitle = null,
            statusChip = HardcoverStatusChip.NOT_LINKED,
            showSyncSwitch = true,
            syncSwitchChecked = false,
            primaryAction = HardcoverAction.CHOOSE_EDITION,
            primaryActionEnabled = true,
            secondaryActions = emptyList(),
            showUpdateDetails = false,
        )
        matched && !excluded -> HardcoverBookDetailsView(
            subtitleRes = matchedSubtitle(link.editionTitle),
            matchedEditionTitle = link.editionTitle?.takeIf { it.isNotBlank() },
            statusChip = HardcoverStatusChip.LINKED,
            showSyncSwitch = true,
            syncSwitchChecked = true,
            primaryAction = HardcoverAction.PUSH,
            primaryActionEnabled = true,
            secondaryActions = listOf(HardcoverAction.CHANGE_EDITION, HardcoverAction.UNLINK),
            showUpdateDetails = true,
        )
        else -> HardcoverBookDetailsView(
            subtitleRes = matchedSubtitle(link.editionTitle),
            matchedEditionTitle = link.editionTitle?.takeIf { it.isNotBlank() },
            statusChip = HardcoverStatusChip.LINKED,
            showSyncSwitch = true,
            syncSwitchChecked = false,
            primaryAction = HardcoverAction.PUSH,
            primaryActionEnabled = false,
            secondaryActions = listOf(HardcoverAction.CHANGE_EDITION, HardcoverAction.UNLINK),
            showUpdateDetails = true,
        )
    }
}

/** What came of fetching Hardcover's cover for the details update. */
private sealed interface CoverFetch {
    data class Downloaded(val bytes: ByteArray) : CoverFetch
    data object Unchanged : CoverFetch
    data object Failed : CoverFetch
}

private const val EINK_BUSY_FRACTION = 0.3f

/** A button's icon, icon gap and horizontal padding around its label. */
private val BUTTON_CHROME_WIDTH = 18.dp + 6.dp + 14.dp * 2 + 2.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HardcoverBookPanel(
    view: HardcoverBookDetailsView,
    busy: Boolean,
    message: HardcoverNotice?,
    onToggleSync: (Boolean) -> Unit,
    onAction: (HardcoverAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val colors = MaterialTheme.colorScheme
    val dividerColor = if (eInk) colors.outline else colors.outlineVariant.copy(alpha = 0.6f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (eInk) colors.surface else colors.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) colors.outline else colors.outlineVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (eInk) colors.surface else colors.surfaceContainerHigh)
                        .then(
                            if (eInk) {
                                Modifier.border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(10.dp))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_hardcover_logo),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.hardcover_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = view.matchedEditionTitle
                            ?.let { stringResource(view.subtitleRes, it) }
                            ?: stringResource(view.subtitleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (view.subtitleRes == R.string.hardcover_book_needs_new_token) {
                            colors.error
                        } else {
                            colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                view.statusChip?.let { chip ->
                    HardcoverStatusPill(chip = chip, eInk = eInk)
                }
            }

            if (busy) {
                val barModifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                if (eInk) {
                    // Held still: an animated bar would make electronic paper repaint until the work ends.
                    LinearProgressIndicator(progress = { EINK_BUSY_FRACTION }, modifier = barModifier, trackColor = dividerColor)
                } else {
                    LinearProgressIndicator(modifier = barModifier, trackColor = dividerColor)
                }
            } else {
                HorizontalDivider(color = dividerColor)
            }

            if (view.showSyncSwitch && view.syncSwitchChecked != null) {
                // One toggleable row, so TalkBack reads the label with the switch's state.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = view.syncSwitchChecked,
                            enabled = !busy,
                            role = Role.Switch,
                            onValueChange = onToggleSync,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.hardcover_book_sync),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.hardcover_book_sync_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = view.syncSwitchChecked,
                        onCheckedChange = null,
                        enabled = !busy,
                    )
                }
                HorizontalDivider(color = dividerColor)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (view.primaryAction != null || view.showUpdateDetails) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val primary = view.primaryAction
                        val measurer = rememberTextMeasurer()
                        val labelStyle = MaterialTheme.typography.labelLarge
                        val density = LocalDensity.current
                        val labels = listOfNotNull(
                            primary?.let { stringResource(it.labelRes) },
                            stringResource(R.string.hardcover_update_details),
                        )
                        // Side by side only when both labels fit unclipped in half the width, in any language.
                        val widest = with(density) {
                            labels.maxOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }.toDp()
                        }
                        val stackButtons = widest + BUTTON_CHROME_WIDTH > (maxWidth - 10.dp) / 2
                        if (primary != null && view.showUpdateDetails) {
                            if (stackButtons) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HardcoverPrimaryButton(
                                        action = primary,
                                        enabled = !busy && view.primaryActionEnabled,
                                        onClick = { onAction(primary) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    HardcoverUpdateDetailsButton(
                                        enabled = !busy,
                                        onClick = { onAction(HardcoverAction.UPDATE_DETAILS) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HardcoverPrimaryButton(
                                        action = primary,
                                        enabled = !busy && view.primaryActionEnabled,
                                        onClick = { onAction(primary) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    HardcoverUpdateDetailsButton(
                                        enabled = !busy,
                                        onClick = { onAction(HardcoverAction.UPDATE_DETAILS) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else if (primary != null) {
                            HardcoverPrimaryButton(
                                action = primary,
                                enabled = !busy && view.primaryActionEnabled,
                                onClick = { onAction(primary) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (view.showUpdateDetails) {
                            HardcoverUpdateDetailsButton(
                                enabled = !busy,
                                onClick = { onAction(HardcoverAction.UPDATE_DETAILS) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (view.secondaryActions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        view.secondaryActions.forEach { secondary ->
                            TextButton(
                                enabled = !busy,
                                onClick = { onAction(secondary) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                secondaryActionIcon(secondary)?.let { icon ->
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(stringResource(secondary.labelRes))
                            }
                        }
                    }
                }

                message?.let { notice ->
                    Text(
                        text = stringResource(notice.textRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notice.isError) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HardcoverStatusPill(
    chip: HardcoverStatusChip,
    eInk: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when {
        eInk -> colors.surface
        chip.linked -> colors.primaryContainer
        else -> colors.surfaceContainerHigh
    }
    val contentColor = when {
        eInk -> colors.onSurface
        chip.linked -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    val borderColor = when {
        eInk -> colors.outline
        chip.linked -> colors.primary.copy(alpha = 0.3f)
        else -> colors.outlineVariant
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (chip.linked) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = stringResource(chip.labelRes),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HardcoverPrimaryButton(
    action: HardcoverAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (action == HardcoverAction.PUSH) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = stringResource(action.labelRes),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HardcoverUpdateDetailsButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.hardcover_update_details),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun secondaryActionIcon(action: HardcoverAction) = when (action) {
    HardcoverAction.CHANGE_EDITION -> Icons.Outlined.Edit
    HardcoverAction.UNLINK -> Icons.Outlined.LinkOff
    else -> null
}

private data class DetailsReviewSession(
    val book: Book,
    val proposals: List<DetailProposal>,
    val coverBytes: ByteArray? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsUpdateSheet(
    session: DetailsReviewSession,
    onApply: (Set<DetailField>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(session) {
        mutableStateOf(session.proposals.filter { it.defaultChecked }.map { it.field }.toSet())
    }
    val eInk = LocalEInk.current
    val preview = remember(session.coverBytes) { session.coverBytes?.let(::previewBitmap) }
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.hardcover_update_details),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.hardcover_update_details_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
            )
            HorizontalDivider()
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                session.proposals.forEach { proposal ->
                    val checked = proposal.field in selected
                    ProposalRow(
                        proposal = proposal,
                        book = session.book,
                        checked = checked,
                        eInk = eInk,
                        preview = preview,
                        onToggle = { on ->
                            selected = if (on) selected + proposal.field else selected - proposal.field
                        },
                    )
                }
            }
            HorizontalDivider()
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onApply(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.hardcover_update_details_apply))
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: DetailProposal,
    book: Book,
    checked: Boolean,
    eInk: Boolean,
    preview: ImageBitmap?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = if (eInk) colors.surface else colors.surfaceContainerLow,
        border = BorderStroke(
            width = if (checked) 1.5.dp else 1.dp,
            color = when {
                checked -> colors.primary
                eInk -> colors.outline
                else -> colors.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onToggle,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(proposalFieldLabel(proposal.field)),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (proposal.field == DetailField.COVER) {
                    CoverComparison(
                        book = book,
                        preview = preview,
                    )
                } else {
                    val nowValue = formatProposalValue(proposal, current = true)
                    val remoteValue = formatProposalValue(proposal, current = false)
                    val maxLines = if (proposal.field == DetailField.DESCRIPTION) 4 else 2
                    Text(
                        text = stringResource(R.string.hardcover_update_details_now, nowValue),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.hardcover_update_details_remote, remoteValue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverComparison(
    book: Book,
    preview: ImageBitmap?,
) {
    val colors = MaterialTheme.colorScheme
    val coverShape = RoundedCornerShape(8.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BookCover(
                book = book,
                shape = coverShape,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = stringResource(R.string.hardcover_update_details_now_label),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(coverShape)
                    .background(colors.surfaceContainerHighest)
                    .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)), coverShape),
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = stringResource(R.string.hardcover_title),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun proposalFieldLabel(field: DetailField): Int = when (field) {
    DetailField.COVER -> R.string.book_metadata_cover_label
    DetailField.TITLE -> R.string.book_metadata_title_label
    DetailField.AUTHOR -> R.string.book_metadata_author_label
    DetailField.DESCRIPTION -> R.string.book_metadata_description_label
    DetailField.SERIES -> R.string.sort_series
}

@Composable
private fun formatProposalValue(proposal: DetailProposal, current: Boolean): String {
    val text = if (current) proposal.currentText else proposal.newText
    if (proposal.field == DetailField.SERIES) {
        val name = text?.takeIf { it.isNotBlank() } ?: return stringResource(R.string.book_metadata_no_series)
        val rawIndex = if (current) proposal.currentSeriesIndex else proposal.newSeriesIndex
        val index = seriesIndexLabel(validSeriesPosition(rawIndex))
        return if (index == null) name else stringResource(R.string.series_position, name, index)
    }
    return text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.book_metadata_no_series)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvaluationConflictsCard(
    conflicts: List<HardcoverEvaluation>,
    busy: Boolean,
    onKeepLocal: (String) -> Unit,
    onUseRemote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (eInk) colors.surface else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (eInk) colors.outline else colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            conflicts.forEachIndexed { index, conflict ->
                if (index > 0) {
                    HorizontalDivider(color = colors.outlineVariant)
                }
                val isRating = conflict.field == PersonalEvaluation.RATING
                val titleRes = if (isRating) {
                    R.string.hardcover_conflict_rating_title
                } else {
                    R.string.hardcover_conflict_review_title
                }
                val noneLabel = stringResource(R.string.book_metadata_no_series)
                val localDisplay = if (isRating) {
                    HardcoverEvaluationPlan.ratingFromLocal(conflict.conflictLocal)?.let(::formatStarRating) ?: noneLabel
                } else {
                    conflict.conflictLocal?.takeIf { it.isNotBlank() } ?: noneLabel
                }
                val remoteDisplay = if (isRating) {
                    HardcoverEvaluationPlan.decodeRemoteRating(conflict.conflictRemote)?.let(::formatStarRating) ?: noneLabel
                } else {
                    conflict.conflictRemote?.takeIf { it.isNotBlank() } ?: noneLabel
                }
                var expanded by remember(conflict.field) { mutableStateOf(false) }
                val maxLines = when {
                    isRating -> 1
                    expanded -> Int.MAX_VALUE
                    else -> 3
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.hardcover_conflict_local, localDisplay),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.hardcover_conflict_remote, remoteDisplay),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!isRating) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (expanded) R.string.book_details_show_less else R.string.book_details_read_more,
                                ),
                            )
                        }
                    }
                    if (conflict.remoteTooLong) {
                        Text(
                            text = stringResource(R.string.hardcover_conflict_review_too_long),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = { onKeepLocal(conflict.field) },
                        ) {
                            Text(stringResource(R.string.hardcover_conflict_keep_local))
                        }
                        if (!conflict.remoteTooLong) {
                            TextButton(
                                enabled = !busy,
                                onClick = { onUseRemote(conflict.field) },
                            ) {
                                Text(stringResource(R.string.hardcover_conflict_use_remote))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatStarRating(stars: Double): String = "${NumberFormat.getNumberInstance().format(stars)} ★"

/** A small bitmap of a downloaded cover for the review sheet. */
private fun previewBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIZE) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()

private const val PREVIEW_SIZE = 320
