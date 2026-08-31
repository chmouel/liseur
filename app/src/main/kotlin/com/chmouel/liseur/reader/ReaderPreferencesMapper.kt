package com.chmouel.liseur.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.ui.graphics.toArgb
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.fonts.UserFont
import com.chmouel.liseur.ui.WidthClass
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Maps the app's reading preferences onto Readium's EPUB preferences.
 *
 * [theme] is passed in rather than read off [ReaderPrefs], which holds
 * the theme as it was *chosen* — and a choice of "follow the app" has no
 * colours until someone knows how the app is drawn. That is a question
 * only Compose can answer, so it is answered once at the top of the
 * reader and the palette arrives here settled.
 *
 * [scroll] is not part of [ReaderPrefs] either: it is answered per book
 * against an app-wide default, so it arrives from the reader's own flow
 * rather than from the shared reading settings.
 */
@OptIn(ExperimentalReadiumApi::class)
fun ReaderPrefs.toEpubPreferences(
    theme: ReaderTheme,
    columnMode: ColumnMode = this.columnMode,
    scroll: Boolean = false,
): EpubPreferences =
    EpubPreferences(
        fontFamily = font.cssName?.let { FontFamily(it) },
        fontSize = fontSize,
        scroll = scroll,
        theme = when (theme) {
            ReaderTheme.LIGHT -> Theme.LIGHT
            ReaderTheme.SEPIA -> Theme.SEPIA
            ReaderTheme.DARK, ReaderTheme.BLACK -> Theme.DARK
        },
        // Readium's own palette is close to ours but not identical (its sepia
        // is #FAF4E8 against our #F6EFDF, its dark #000000 against our
        // #1F1F1F). The page is inset from the screen edges, so any
        // difference shows up as bands above and below the text. Pass our
        // colours for every theme so the two always match.
        backgroundColor = Color(theme.background.toArgb()),
        textColor = Color(theme.foreground.toArgb()),
        lineHeight = lineHeight,
        pageMargins = pageMargins,
        // Left unset on Auto rather than sent as ColumnCount.AUTO, so a
        // reader who never touches this setting gets exactly the page
        // Readium would have laid out on its own.
        columnCount = when (columnMode) {
            ColumnMode.AUTO -> null
            ColumnMode.ONE -> ColumnCount.ONE
            ColumnMode.TWO -> ColumnCount.TWO
        },
        // Readium CSS applies the font size whatever the publisher styles
        // say, but only applies the advanced settings (line height,
        // margins…) when they are turned off. So they are only turned off
        // for those: turning them off rewrites the size of every element
        // on the page, and having that happen because a font-size slider
        // crossed its default made the page reflow far beyond the change
        // that was asked for — and the reading position with it.
        publisherStyles = if (lineHeight != null || pageMargins != null) {
            false
        } else {
            null
        },
    )

/**
 * Column width asked for in two-column mode, as a share of the viewport.
 *
 * Anything at or below half leaves room for two columns at every width;
 * staying under it absorbs the page gutter and the column gap without
 * having to know what they are.
 */
private const val TWO_COLUMN_WIDTH_VW = 45.0

/**
 * What the page paints behind text the reader has marked out.
 *
 * Readium CSS ships `#b4d8fe` and its night rule repeats the same value
 * with the selected text left at `inherit`, so on a dark page the mark is
 * the theme's pale ink on a pale blue block — barely readable, which is
 * what issue #117 reported.
 *
 * The answer is one translucent colour rather than four opaque ones. The
 * blend happens in the page, against whichever background the theme has
 * put there, so white, beige, dark grey and black each get their own
 * shade for free — and so does any theme added later. It also has to be
 * this way round: RS properties are injected when a resource loads, from
 * the configuration the navigator fragment was *built* with, so a value
 * chosen per theme could only follow a theme changed mid-book by
 * rebuilding the fragment under the reader — at every tap of the theme
 * buttons, and again when the app turns itself dark at dusk.
 *
 * Over white it lands within a couple of points of the blue that was
 * there before, so the two themes nobody complained about keep the look
 * they had.
 *
 * Carried as a raw override rather than through
 * `RsProperties.selectionBackgroundColor`, whose `Color.Hex` refuses
 * anything but three or six hex digits — an alpha channel among them.
 */
private const val SELECTION_BACKGROUND = "#4A90E266"

/**
 * The ink of selected text: the page's own, whatever the theme made it.
 *
 * Night mode pins this to `inherit`, which is the same answer, and day
 * mode leaves it unset — `color: var(--RS__selectionTextColor)` is then
 * invalid at computed-value time and `color` is inherited anyway. Saying
 * it once, for every theme, means the rule reads as what it does.
 */
private const val SELECTION_TEXT = "currentColor"

/** The two of them under the names Readium CSS reads them by. */
private val selectionOverrides = mapOf(
    "--RS__selectionBackgroundColor" to SELECTION_BACKGROUND,
    "--RS__selectionTextColor" to SELECTION_TEXT,
)

/**
 * The stylesheet's own properties, as the navigator is built with them.
 *
 * Split out from [epubNavigatorConfiguration] to be readable on the JVM:
 * the configuration around it declares font faces by URL, and a URL is
 * Android's, so building one at all needs a device.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun readingRsProperties(columnMode: ColumnMode): RsProperties = RsProperties(
    colCount = when (columnMode) {
        ColumnMode.AUTO -> null
        ColumnMode.ONE -> ColCount.ONE
        ColumnMode.TWO -> ColCount.TWO
    },
    colWidth = when (columnMode) {
        ColumnMode.TWO -> Length.Vw(TWO_COLUMN_WIDTH_VW)
        else -> null
    },
    overrides = selectionOverrides,
)

/**
 * The column mode a window this wide can actually carry out.
 *
 * Two things happen here, at either end of the range.
 *
 * A phone cannot fit two columns of text worth reading, and the control
 * that sets this is hidden below [WidthClass.MEDIUM] — so a reader who
 * picks two columns on a tablet and then rotates it, or folds it, or
 * opens the book in a narrow split-screen pane, would otherwise be stuck
 * with a setting they can no longer see or undo. Narrow windows fall
 * back to Auto, which on a phone is the untouched Readium layout: one
 * column, exactly as it has always been.
 *
 * Above that, Auto means two. Readium's own default is a single column
 * whatever the screen, which on a tablet is a line of text a foot long
 * and hard to follow back to; a reader who never opens the typography
 * sheet should still get a page laid out for the screen they are
 * holding. One column remains a choice, not an accident.
 */
fun ColumnMode.effectiveFor(widthClass: WidthClass): ColumnMode = when {
    widthClass == WidthClass.COMPACT -> ColumnMode.AUTO
    this == ColumnMode.AUTO -> ColumnMode.TWO
    else -> this
}

/**
 * Navigator configuration declaring the bundled reading fonts, served from
 * the app's assets, and taking over what happens when text is selected.
 *
 * [columnMode] is also applied here, at the stylesheet's own level, and
 * not only as a user preference. Readium keeps `--USER__colCount` behind
 * a `min-width: 60em` media query, so on anything narrower than a large
 * tablet — a 7" e-ink reader, a phone held sideways — asking for two
 * columns through preferences alone does nothing. Setting the RS
 * properties instead reaches the unconditional `:root` rule.
 *
 * The column width has to go with it. `column-count` is a ceiling, not a
 * demand — the used count is the smaller of it and what the column width
 * allows — and Readium's default `--RS__colWidth` is 45em, so two columns
 * only actually appear on a screen wide enough for 90em of text. Above
 * 60em Readium answers this by setting the width to `auto`; the Kotlin
 * `Length` has no `auto`, so half the viewport does the same job: two
 * columns fit at any width, and the ceiling stops it becoming three.
 * Nothing below [WidthClass.MEDIUM] ever gets here, because
 * [effectiveFor] has already turned it back into Auto.
 *
 * These are read when the book opens rather than watched, so a change
 * lands on the next book; the preference above covers the live case on
 * screens wide enough for it.
 *
 * The system's own selection menu is refused so the app can put its own
 * bar next to the words instead: the platform bar floats where it likes,
 * offers actions a book has no use for, and cannot show highlight colours.
 * Taking it over means taking on what it did for free, so the callback
 * reports the selection on every change and its disappearance on the way
 * out — see the callback itself for why both matter. What the selection
 * is *painted* in is set here too; see [SELECTION_BACKGROUND].
 *
 * [scroll] switches off Readium's page turns, which in a scrolled book
 * are a whole chapter at a time: a sideways swipe would throw the reader
 * out of the chapter they are in the middle of, and the volume keys are
 * given a screenful of scrolling instead (see `PageTurner`).
 */
@OptIn(ExperimentalReadiumApi::class)
fun epubNavigatorConfiguration(
    columnMode: ColumnMode = ColumnMode.Default,
    scroll: Boolean = false,
    userFonts: List<UserFont> = emptyList(),
    onTextSelected: () -> Unit = {},
    onSelectionCleared: () -> Unit = {},
): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")
        disablePageTurnsWhileScrolling = scroll
        readiumCssRsProperties = readingRsProperties(columnMode)
        selectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                onTextSelected()
                // The mode has to be accepted or the web view drops the
                // selection along with it; emptying the menu is what keeps
                // the platform bar from ever being drawn.
                menu.clear()
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                // Dragging a handle to stretch the selection never starts a
                // second action mode: the web view invalidates the one it
                // already has, which arrives here and nowhere else. Asking
                // again is therefore the only way the passage the reader
                // actually marked out reaches us — without it every
                // highlight is the single word the long press landed on,
                // and the reader is left doing it again.
                onTextSelected()
                menu.clear()
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

            override fun onDestroyActionMode(mode: ActionMode) {
                // The web view has let the selection go — a tap on the page,
                // a page turn. Our own bar is the only thing still pointing
                // at words that are no longer marked.
                onSelectionCleared()
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.LITERATA.cssName))) {
            addFontFace {
                addSource("fonts/Literata.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(200..900)
            }
            addFontFace {
                addSource("fonts/Literata-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(200..900)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.VOLLKORN.cssName))) {
            addFontFace {
                addSource("fonts/Vollkorn.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(400..900)
            }
            addFontFace {
                addSource("fonts/Vollkorn-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(400..900)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.ATKINSON.cssName))) {
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Regular.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(400..400)
            }
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(400..400)
            }
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Bold.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(700..700)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.INTER.cssName))) {
            addFontFace {
                addSource("fonts/Inter.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(100..900)
            }
            addFontFace {
                addSource("fonts/Inter-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(100..900)
            }
        }

        // Every imported font is declared, not just the chosen one, so
        // switching between them is the instant change it already is for
        // the four above — the web view only fetches the family a page
        // actually asks for. Each is one face: a single file cannot carry
        // a real italic or bold, and the page synthesises them.
        //
        // The weight range arrives already clamped to 1..1000, because
        // setFontWeight() asserts that and a malformed `fvar` would
        // otherwise bring the reader down while it was being configured.
        userFonts.forEach { font ->
            addFontFamilyDeclaration(FontFamily(font.cssName)) {
                addFontFace {
                    addSource(UserFontResources.url(font))
                    setFontStyle(if (font.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                    val weight = font.weightRange ?: font.staticWeight..font.staticWeight
                    setFontWeight(weight)
                }
            }
        }
    }
