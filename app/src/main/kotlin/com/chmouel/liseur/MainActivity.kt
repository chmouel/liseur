package com.chmouel.liseur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.chmouel.liseur.reader.ReaderActivity
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.theme.LiseurTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiseurTheme {
                LibraryScreen(onOpenBook = rememberOpenBookAction())
            }
        }
    }
}

/** SAF picker for an EPUB; keeps read access and hands off to the reader. */
@Composable
private fun rememberOpenBookAction(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.startActivity(ReaderActivity.intent(context, uri.toString()))
        }
    }
    return { launcher.launch(arrayOf("application/epub+zip")) }
}
