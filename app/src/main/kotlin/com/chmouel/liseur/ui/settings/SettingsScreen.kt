package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.label
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private const val LISEUR_SYNC_REPO_URL = "https://github.com/chmouel/liseur-sync"

/** Everything about the app that is not about one particular book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    readingThemeChoice: ReaderThemeChoice,
    dynamicColorAvailable: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    onAppLanguage: (AppLanguage) -> Unit = {},
    onOpenAccount: () -> Unit,
    onOpenReadingAppearance: () -> Unit,
    onOpenReadingNavigation: () -> Unit,
    onOpenWatchedFolders: () -> Unit = {},
    onOpenHiddenBooks: () -> Unit,
    libraryFolders: Flow<List<LibraryFolder>>,
    hiddenBooks: Flow<List<Book>> = emptyFlow(),
    onOpenSettingsBackup: () -> Unit,
    server: Flow<RemoteServer?>,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Rows a build flavor adds to Reading: read aloud, in both builds. */
    flavorReadingRows: @Composable () -> Unit = {},
    /** Rows for the AI features and the services they use: translation, the reading companion, servers and keys. */
    intelligenceRows: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val folders by libraryFolders.collectAsStateWithLifecycle(emptyList())
    val hidden by hiddenBooks.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    // widthIn must come before fillMaxWidth: fillMaxSize would
                    // pin the width to the window first, leaving the cap with a
                    // fixed constraint it cannot narrow.
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Settings rows are a column of sentences. Run across a
                    // tablet they become a column of sentences with a foot of
                    // nothing after each one, and the switch at the end is a
                    // reach away from the label that explains it.
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                SettingsGroup(stringResource(R.string.settings_appearance)) {
                    ChipRow(
                        title = stringResource(R.string.settings_theme),
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        label = { stringResource(it.label) },
                        onSelected = onThemeMode,
                    )
                    if (dynamicColorAvailable) {
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_dynamic_color),
                            subtitle = stringResource(R.string.settings_dynamic_color_detail),
                            checked = settings.dynamicColor,
                            onCheckedChange = onDynamicColor,
                        )
                    }
                    RowDivider()
                    AppLanguageRow(
                        selected = appLanguage,
                        onSelected = onAppLanguage,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_reading)) {
                    // The theme above is the app's, and the page has one of
                    // its own; a reader who turns this screen dark and finds
                    // their books still white has nowhere to look unless
                    // this row says where. Naming the "Aa" button here is
                    // half of what it is for.
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.TextFormat, contentDescription = null) },
                        title = stringResource(R.string.settings_reading_appearance),
                        subtitle = stringResource(
                            R.string.settings_reading_appearance_current,
                            stringResource(readingThemeChoice.label),
                        ),
                        onClick = onOpenReadingAppearance,
                    )
                    RowDivider()
                    // The section behind this row had grown to eight
                    // switches, a chip row and two rows that only some
                    // screens ever show, which is a list to be read
                    // through rather than a place to go. The dictionary
                    // went with it: looking a word up is something you
                    // do with a book open.
                    ConnectionRow(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.settings_reading_navigation),
                        // Which side turns the page is nothing to a
                        // scrolled book, so a scrolling reader is told
                        // that instead of a side that does not apply.
                        subtitle = if (settings.scrollMode) {
                            stringResource(R.string.settings_reading_navigation_scrolling)
                        } else {
                            stringResource(
                                R.string.settings_reading_navigation_paged,
                                stringResource(settings.tapZones.label),
                            )
                        },
                        onClick = onOpenReadingNavigation,
                    )
                    flavorReadingRows()
                }

                val showLibraryHelp = remember { mutableStateOf(false) }
                if (showLibraryHelp.value) {
                    val uriHandler = LocalUriHandler.current
                    val helpBody = stringResource(
                        R.string.settings_library_help_body,
                        LISEUR_SYNC_REPO_URL,
                    )
                    AlertDialog(
                        onDismissRequest = { showLibraryHelp.value = false },
                        title = { Text(stringResource(R.string.settings_library_help_title)) },
                        text = {
                            // The address is part of the sentence; mark it
                            // up after the fact so translations can move it.
                            val linkColor = MaterialTheme.colorScheme.primary
                            val annotated = remember(helpBody) {
                                buildAnnotatedString {
                                    append(helpBody)
                                    val start = helpBody.indexOf(LISEUR_SYNC_REPO_URL)
                                    if (start >= 0) {
                                        addLink(
                                            LinkAnnotation.Url(LISEUR_SYNC_REPO_URL),
                                            start,
                                            start + LISEUR_SYNC_REPO_URL.length,
                                        )
                                        addStyle(
                                            SpanStyle(color = linkColor),
                                            start,
                                            start + LISEUR_SYNC_REPO_URL.length,
                                        )
                                    }
                                }
                            }
                            ClickableText(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                onClick = { offset ->
                                    annotated
                                        .getLinkAnnotations(offset, offset)
                                        .firstOrNull()
                                        ?.let { uriHandler.openUri(LISEUR_SYNC_REPO_URL) }
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showLibraryHelp.value = false }) {
                                Text(stringResource(R.string.dismiss))
                            }
                        },
                    )
                }
                SettingsGroup(
                    title = stringResource(R.string.settings_library),
                    onHelp = { showLibraryHelp.value = true },
                    helpDescription = stringResource(R.string.settings_library_help_title),
                ) {
                    val catalog by server.collectAsStateWithLifecycle(null)
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                        title = stringResource(R.string.server_account),
                        // Connected: say to what. Not connected: say what it
                        // would do. A row that always reads as an invitation
                        // makes a connected server look unconnected.
                        subtitle = catalog?.let {
                            val user = it.username?.takeIf { name -> name.isNotBlank() }
                            if (user == null) {
                                // An open catalog, or a connection that
                                // only carries positions, has no name to
                                // be signed in under.
                                stringResource(R.string.server_connected_anon, it.baseUrl)
                            } else {
                                stringResource(R.string.server_connected, it.baseUrl, user)
                            }
                        } ?: stringResource(R.string.settings_account_detail),
                        onClick = onOpenAccount,
                    )
                    RowDivider()
                    ConnectionRow(
                        icon = {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.library_folders),
                        subtitle = if (folders.isEmpty()) {
                            stringResource(R.string.library_folders_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.library_folders_count,
                                folders.size,
                                folders.size,
                            )
                        },
                        onClick = onOpenWatchedFolders,
                    )
                    RowDivider()
                    ConnectionRow(
                        icon = {
                            Icon(
                                Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.hidden_books),
                        subtitle = if (hidden.isEmpty()) {
                            stringResource(R.string.hidden_books_summary)
                        } else {
                            pluralStringResource(
                                R.plurals.library_book_count,
                                hidden.size,
                                hidden.size,
                            )
                        },
                        onClick = onOpenHiddenBooks,
                    )
                    RowDivider()
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                        title = stringResource(R.string.settings_backup_title),
                        subtitle = stringResource(R.string.settings_backup_detail),
                        onClick = onOpenSettingsBackup,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_intelligence)) {
                    intelligenceRows()
                }

                SettingsGroup(stringResource(R.string.settings_about)) {
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        title = stringResource(R.string.about_open),
                        subtitle = stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

@Composable
internal fun BackupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } },
        colors = if (enabled) {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        } else {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/**
 * A server row that says where it points once it points anywhere.
 *
 * The icon is the same family the account screens themselves use, so
 * the row reads as a door into them rather than a new kind of thing.
 */
@Composable
internal fun ConnectionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /** What accessibility reads for [subtitle] when it shows more than words, such as a flag. */
    subtitleDescription: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                modifier = subtitleDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier,
            )
        },
        leadingContent = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
