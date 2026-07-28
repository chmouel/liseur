package com.chmouel.liseur.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.reader.chrome.PageTurner
import com.chmouel.liseur.ui.theme.LiseurTheme
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toAbsoluteUrl

class ReaderActivity : FragmentActivity() {

    private var navigator: EpubNavigatorFragment? = null
    private var pageTurner: PageTurner? = null

    private val bookUrl: AbsoluteUrl? by lazy {
        (intent.getStringExtra(EXTRA_URL)?.toUri() ?: intent.data)?.toAbsoluteUrl()
    }

    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.factory(checkNotNull(bookUrl))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Publication doesn't survive process death, so saved navigator
        // fragment state can't be restored; drop it and reopen the book at
        // the persisted locator instead. Rotation is handled without
        // recreation via android:configChanges.
        super.onCreate(null)

        val url = bookUrl
        if (url == null) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            LiseurTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val s = state) {
                    ReaderViewModel.UiState.Loading ->
                        ReaderLoadingScreen()

                    is ReaderViewModel.UiState.Failure ->
                        ReaderErrorScreen(message = s.message, onBack = ::finish)

                    is ReaderViewModel.UiState.Ready -> {
                        // The factory must be installed before AndroidFragment
                        // instantiates the navigator, hence remember {} and
                        // not SideEffect {}.
                        remember(s.navigatorFactory) {
                            s.navigatorFactory.createFragmentFactory(
                                initialLocator = viewModel.lastLocator ?: s.initialLocator,
                                initialPreferences = viewModel.prefs.value.toEpubPreferences(),
                                configuration = epubNavigatorConfiguration(),
                            ).also { supportFragmentManager.fragmentFactory = it }
                        }
                        ReaderScreen(
                            publication = s.publication,
                            prefsFlow = viewModel.prefs,
                            onLocatorChanged = viewModel::onLocatorChanged,
                            onNavigatorChanged = { navigator = it },
                            onPageTurnerChanged = { pageTurner = it },
                            onPrefsAction = remember {
                                ReaderPrefsActions(
                                    setFont = viewModel::setFont,
                                    setFontSize = viewModel::setFontSize,
                                    setTheme = viewModel::setTheme,
                                    setLineHeight = viewModel::setLineHeight,
                                    setPageMargins = viewModel::setPageMargins,
                                    setBrightness = viewModel::setBrightness,
                                    setPageTurnAnimation = viewModel::setPageTurnAnimation,
                                )
                            },
                            onBack = ::finish,
                        )
                    }
                }
            }
        }
    }

    /** Volume keys turn pages, like the Kindle app's optional setting. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val turner = pageTurner ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                turner.turn(forward = true)
                true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                turner.turn(forward = false)
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        when {
            navigator != null &&
                (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            -> true

            else -> super.onKeyUp(keyCode, event)
        }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
