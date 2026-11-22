package com.chmouel.liseur.providers

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.UnreadableCredentialException
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.hardcover.EditionChoice
import com.chmouel.liseur.hardcover.EditionIcon
import com.chmouel.liseur.hardcover.HardcoverAccount
import com.chmouel.liseur.hardcover.HardcoverCandidates
import com.chmouel.liseur.hardcover.HardcoverClient
import com.chmouel.liseur.hardcover.HardcoverConnectOutcome
import com.chmouel.liseur.hardcover.HardcoverDatabase
import com.chmouel.liseur.hardcover.HardcoverEdition
import com.chmouel.liseur.hardcover.HardcoverPlan
import com.chmouel.liseur.hardcover.HardcoverPushOutcome
import com.chmouel.liseur.hardcover.HardcoverPushWorker
import com.chmouel.liseur.hardcover.HardcoverRepository
import com.chmouel.liseur.hardcover.LocalReading
import com.chmouel.liseur.hardcover.editionRow
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.settings.SwitchRow
import com.chmouel.liseur.ui.settings.search.SettingsFocus
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        return LocalReading(
            status = status,
            chosen = override != FinishedOverride.NONE && override != FinishedOverride.UNREAD,
            progression = progress?.totalProgression,
            startedOn = database.readingSessionDao().startedAtFor(bookUrl).minOrNull()?.let(::day),
            finishedOn = book.finishedAt?.let(::day),
            rating = HardcoverPlan.ratingFromLocal(rating),
        )
    }

    private fun day(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

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

    /** One book's standing with Hardcover, on its details page, while an account is connected. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun BookDetailsSection(bookUrl: String, modifier: Modifier) {
        if (!remember { used() }) return
        val account by hardcover.account.collectAsState(initial = null)
        if (account == null) return
        val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsState(initial = null)
        val ui = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember(bookUrl) { mutableStateOf<Int?>(null) }
        var choices by remember(bookUrl) { mutableStateOf<PickerSession?>(null) }
        val excluded = link?.excluded == true
        fun busyWhile(work: suspend () -> Unit) {
            busy = true
            message = null
            ui.launch {
                work()
                busy = false
            }
        }
        fun openChoices(push: Boolean) {
            busyWhile {
                when (val found = hardcover.candidates(bookUrl)) {
                    is RemoteResult.Ok -> choices = PickerSession(found.value, push)
                    is RemoteResult.Failed -> message = failureMessage(found.reason)
                }
            }
        }

        Column(modifier) {
            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.hardcover_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = link?.takeIf { it.matched }?.editionTitle
                    ?.let { stringResource(R.string.hardcover_book_matched, it) }
                    ?: stringResource(R.string.hardcover_book_unmatched),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // One toggleable row, so TalkBack reads the label with the switch's state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = !excluded,
                        role = Role.Switch,
                        onValueChange = { on -> ui.launch { hardcover.setExcluded(bookUrl, !on) } },
                    )
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.hardcover_book_sync),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = !excluded, onCheckedChange = null)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { openChoices(push = false) },
                ) { Text(stringResource(R.string.hardcover_book_choose)) }
                OutlinedButton(
                    enabled = !busy && !excluded,
                    onClick = {
                        // Nothing to report against until an edition is picked, and asking
                        // Hardcover to match it again would only repeat the search.
                        if (link?.matched != true) {
                            openChoices(push = true)
                        } else {
                            busyWhile {
                                when (val outcome = hardcover.push(bookUrl, asked = true)) {
                                    // A match this device lost is still worth asking for.
                                    HardcoverPushOutcome.NeedsMatch -> when (val found = hardcover.candidates(bookUrl)) {
                                        is RemoteResult.Ok -> choices = PickerSession(found.value, push = true)
                                        is RemoteResult.Failed -> message = failureMessage(found.reason)
                                    }
                                    else -> message = outcomeMessage(outcome)
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.hardcover_push)) }
                if (busy) BusyIndicator()
            }
            message?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        choices?.let { session ->
            EditionPicker(
                session = session,
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
                            else -> message = choiceMessage(chosen)
                        }
                    }
                },
                onDismiss = { choices = null },
            )
        }
    }

    /** "Send to Hardcover" in the reader's menu, while an account is connected. */
    @Composable
    fun ReaderMenuItem(
        bookUrl: String,
        savePlace: suspend () -> Boolean,
        close: () -> Unit,
        requestEdition: ((bookUrl: String, push: Boolean) -> Unit)? = null,
    ) {
        if (!remember { used() }) return
        val account by hardcover.account.collectAsState(initial = null)
        if (account == null) return
        val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsState(initial = null)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hardcover_push)) },
            leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
            onClick = {
                close()
                if (link?.matched != true && requestEdition != null) {
                    requestEdition(bookUrl, true)
                    return@DropdownMenuItem
                }
                scope.launch {
                    // The place on screen can be ahead of the stored one until it is saved.
                    val saved = withContext(Dispatchers.Main.immediate) { savePlace() }
                    if (!saved) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.reader_position_not_saved, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
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
            },
        )
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
        currentEditionId: Long?,
        onChoose: (HardcoverEdition) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val editions = session.context.editions
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.hardcover_book_choose)) },
            text = {
                if (editions.isEmpty()) {
                    Text(stringResource(R.string.hardcover_editions_none))
                } else {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        editions.forEach { edition ->
                            val row = editionRow(edition, currentEditionId)
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        imageVector = if (row.icon == EditionIcon.AUDIOBOOK) {
                                            Icons.Outlined.Headphones
                                        } else {
                                            Icons.Outlined.MenuBook
                                        },
                                        contentDescription = row.formatLabel,
                                    )
                                },
                                headlineContent = { Text(row.headline) },
                                supportingContent = {
                                    Column {
                                        if (row.supporting.isNotEmpty()) Text(row.supporting)
                                        row.pages?.let {
                                            Text(pluralStringResource(R.plurals.hardcover_edition_pages, it, it))
                                        }
                                        row.readers?.let {
                                            Text(pluralStringResource(R.plurals.hardcover_edition_readers, it, it))
                                        }
                                    }
                                },
                                trailingContent = if (row.current) {
                                    {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = stringResource(R.string.hardcover_edition_current),
                                        )
                                    }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().clickable { onChoose(edition) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
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
        fun outcomeMessage(outcome: HardcoverPushOutcome): Int = when (outcome) {
            HardcoverPushOutcome.Pushed -> R.string.hardcover_pushed
            HardcoverPushOutcome.UpToDate -> R.string.hardcover_up_to_date
            HardcoverPushOutcome.NeedsMatch -> R.string.hardcover_needs_match
            HardcoverPushOutcome.SwitchedOff -> R.string.hardcover_push_off
            HardcoverPushOutcome.NotConnected -> R.string.hardcover_error_token
            is HardcoverPushOutcome.Failed -> failureMessage(outcome.reason)
        }

        @StringRes
        /** Nothing to say for a choice made only, which the caller follows up itself. */
        fun choiceMessage(choice: EditionChoice): Int? = when (choice) {
            EditionChoice.Chosen -> null
            is EditionChoice.Pushed -> outcomeMessage(choice.outcome)
            EditionChoice.SwitchedOff -> R.string.hardcover_push_off
            EditionChoice.NotConnected, EditionChoice.OtherAccount -> R.string.hardcover_error_token
            EditionChoice.AlreadyGone -> null
            is EditionChoice.Failed -> failureMessage(choice.reason)
        }
    }
}

/** What an open picker was asked for: the lookup's validation context and whether choosing also pushes. */
private data class PickerSession(val context: HardcoverCandidates, val push: Boolean)
