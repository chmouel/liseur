package com.chmouel.liseur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chmouel.liseur.reader.ReaderActivity
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.theme.LiseurTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiseurTheme {
                LibraryRoute()
            }
        }
    }
}

@Composable
private fun LibraryRoute(
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
            context.startActivity(ReaderActivity.intent(context, book.url))
        },
    )
}
