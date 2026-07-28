package com.chmouel.liseur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chmouel.liseur.reader.ReaderActivity
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.settings.CalibreAccountScreen
import com.chmouel.liseur.ui.settings.CalibreAccountViewModel
import com.chmouel.liseur.ui.theme.LiseurTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiseurTheme {
                LiseurApp()
            }
        }
    }
}

private enum class Screen { LIBRARY, CALIBRE_ACCOUNT }

@Composable
private fun LiseurApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }

    when (screen) {
        Screen.LIBRARY -> LibraryRoute(onOpenAccount = { screen = Screen.CALIBRE_ACCOUNT })
        Screen.CALIBRE_ACCOUNT -> {
            BackHandler { screen = Screen.LIBRARY }
            CalibreAccountRoute(onBack = { screen = Screen.LIBRARY })
        }
    }
}

@Composable
private fun CalibreAccountRoute(
    onBack: () -> Unit,
    viewModel: CalibreAccountViewModel = viewModel(factory = CalibreAccountViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CalibreAccountScreen(
        state = state,
        onUrlChange = viewModel::setUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onConnect = viewModel::connect,
        onRetryCapabilities = viewModel::retryCapabilities,
        onKoboToken = viewModel::setKoboToken,
        onDisconnect = viewModel::disconnect,
        onBack = onBack,
    )
}

@Composable
private fun LibraryRoute(
    onOpenAccount: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val openBook = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.importBook(uri)
            context.startActivity(ReaderActivity.intent(context, uri.toString()))
        }
    }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    LibraryScreen(
        state = state,
        onOpenBook = { openBook.launch(arrayOf("application/epub+zip")) },
        onAddFolder = { addFolder.launch(null) },
        onBookSelected = { book ->
            book.openableUrl?.let { context.startActivity(ReaderActivity.intent(context, it)) }
        },
        onOpenAccount = onOpenAccount,
    )
}
