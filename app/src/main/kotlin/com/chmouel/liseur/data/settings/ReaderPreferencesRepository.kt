package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerPrefsStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_preferences",
)

/**
 * Persists the reading preferences (font, size, theme, brightness…).
 *
 * Takes the store rather than a [Context] so the round trip can be
 * tested against a real one on a temporary file, without an emulator;
 * `ReadingPaceRepository` is the same shape for the same reason.
 */
class ReaderPreferencesRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.readerPrefsStore)

    private object Keys {
        val FONT = stringPreferencesKey("font")
        val FONT_SIZE = doublePreferencesKey("font_size")
        val THEME = stringPreferencesKey("theme")
        val LINE_HEIGHT = doublePreferencesKey("line_height")
        val PAGE_MARGINS = doublePreferencesKey("page_margins")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val PAGE_TURN_STYLE = stringPreferencesKey("page_turn_style")

        /**
         * What the page turn was before it had three answers, kept only
         * to be read: off meant the instant jump, and a reader who chose
         * that should not find the page lifting again after an update.
         */
        val LEGACY_PAGE_TURN_ANIMATION = booleanPreferencesKey("page_turn_animation")
        val FOOTER_MODE = stringPreferencesKey("footer_mode")
        val FOOTER_LEFT = stringPreferencesKey("footer_left")
        val FOOTER_RIGHT = stringPreferencesKey("footer_right")
        val COLUMN_MODE = stringPreferencesKey("column_mode")
        val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
        val TEXT_ALIGN = stringPreferencesKey("text_align")
        val FONT_WEIGHT = stringPreferencesKey("font_weight")
        val HYPHENS = booleanPreferencesKey("hyphens")
        val LETTER_SPACING = doublePreferencesKey("letter_spacing")
        val WORD_SPACING = doublePreferencesKey("word_spacing")
        val PARAGRAPH_SPACING = doublePreferencesKey("paragraph_spacing")
    }

    /**
     * Sanitized on the way out as well as on the way in: this file is on
     * a device, and a value that has been edited underneath us is a
     * crash in `EpubPreferences` rather than a wrong page.
     */
    val prefs: Flow<ReaderPrefs> = store.data.map { p ->
        ReaderPrefs(
            font = ReadingFont.fromId(p[Keys.FONT]),
            fontSize = p[Keys.FONT_SIZE] ?: ReaderPrefs.DEFAULT_FONT_SIZE,
            themeChoice = ReaderThemeChoice.fromId(p[Keys.THEME]),
            lineHeight = p[Keys.LINE_HEIGHT],
            pageMargins = p[Keys.PAGE_MARGINS],
            brightness = p[Keys.BRIGHTNESS],
            pageTurnStyle = pageTurnStyleFrom(
                stored = p[Keys.PAGE_TURN_STYLE],
                legacyAnimation = p[Keys.LEGACY_PAGE_TURN_ANIMATION],
            ),
            footerMode = FooterMode.fromId(p[Keys.FOOTER_MODE]),
            footerLeft = FooterField.fromId(p[Keys.FOOTER_LEFT], FooterSlot.LEFT),
            footerRight = FooterField.fromId(p[Keys.FOOTER_RIGHT], FooterSlot.RIGHT),
            columnMode = ColumnMode.fromId(p[Keys.COLUMN_MODE]),
            autoScrollSpeed = p[Keys.AUTO_SCROLL_SPEED] ?: AutoScrollPreference.DEFAULT_STEP,
            textAlign = ReaderTextAlign.fromId(p[Keys.TEXT_ALIGN]),
            fontWeight = ReaderFontWeight.fromId(p[Keys.FONT_WEIGHT]),
            hyphens = p[Keys.HYPHENS],
            letterSpacing = p[Keys.LETTER_SPACING],
            wordSpacing = p[Keys.WORD_SPACING],
            paragraphSpacing = p[Keys.PARAGRAPH_SPACING],
        ).sanitized()
    }

    suspend fun setFont(font: ReadingFont) {
        store.edit { it[Keys.FONT] = font.id }
    }

    /**
     * The size written down for the reader, or null while they read at
     * the default. A stored number too damaged to use is null too, since
     * it reads as the default.
     */
    suspend fun storedFontSize(): Double? =
        store.data.first()[Keys.FONT_SIZE]?.takeIf { it.isFinite() && it >= 0.0 }

    /** Hands the size back to the default. */
    suspend fun clearFontSize() {
        store.edit { it.remove(Keys.FONT_SIZE) }
    }

    suspend fun setFontSize(size: Double) {
        store.edit { it[Keys.FONT_SIZE] = TypographyRange.FONT_SIZE.require(size) }
    }

    suspend fun setTheme(theme: ReaderThemeChoice) {
        store.edit { it[Keys.THEME] = theme.id }
    }

    suspend fun setLineHeight(value: Double?) {
        setNullableDouble(Keys.LINE_HEIGHT, TypographyRange.LINE_HEIGHT.sanitize(value))
    }

    suspend fun setPageMargins(value: Double?) {
        setNullableDouble(Keys.PAGE_MARGINS, TypographyRange.PAGE_MARGINS.sanitize(value))
    }

    suspend fun setBrightness(value: Float?) {
        store.edit {
            if (value == null) it.remove(Keys.BRIGHTNESS) else it[Keys.BRIGHTNESS] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setPageTurnStyle(style: PageTurnStyle) {
        store.edit { it[Keys.PAGE_TURN_STYLE] = style.id }
    }

    suspend fun setFooterMode(mode: FooterMode) {
        store.edit { it[Keys.FOOTER_MODE] = mode.id }
    }

    /**
     * Steps the middle of the footer on to its next figure, and says
     * which one it landed on.
     *
     * Read and write in the same [store] edit rather than from the
     * settings the reader last saw. A tap is answered by DataStore on
     * a writer of its own, and a reader tapping twice in quick
     * succession has both taps looking at the same starting figure:
     * they choose the same successor, the second write says what the
     * first already said, and a step is lost. What is read here is
     * whatever the last completed write left behind.
     *
     * The choice is handed back for the same reason. The note the
     * footer raises names the figure the tap chose, and a tap that
     * worked this out for itself would be working from the settings
     * the footer was drawn with, which the tap before it has already
     * moved on.
     */
    suspend fun cycleFooterMode(): FooterMode = cycleFooter { mode, left, right ->
        nextFooterMode(mode, left, right).also { this[Keys.FOOTER_MODE] = it.id }
    }

    /** Steps one edge on to its next figure, and says which one. */
    suspend fun cycleFooterField(slot: FooterSlot): FooterField =
        cycleFooter { mode, left, right ->
            nextFooterField(slot, mode, left, right).also {
                this[slot.key] = it.id
            }
        }

    /**
     * One footer step: the three settings as they stand are handed to
     * [choose], which writes its answer and returns it.
     *
     * The answer is picked up from inside the edit rather than read
     * back afterwards, so it is the value this step wrote and not
     * whatever a step behind it has written since.
     */
    private suspend fun <T : Any> cycleFooter(
        choose: MutablePreferences.(FooterMode, FooterField, FooterField) -> T,
    ): T {
        var chosen: T? = null
        store.edit { at ->
            chosen = at.choose(
                FooterMode.fromId(at[Keys.FOOTER_MODE]),
                FooterField.fromId(at[Keys.FOOTER_LEFT], FooterSlot.LEFT),
                FooterField.fromId(at[Keys.FOOTER_RIGHT], FooterSlot.RIGHT),
            )
        }
        return checkNotNull(chosen) { "the footer was stepped on without choosing a figure" }
    }

    /** What one edge of the reading footer shows. */
    suspend fun setFooterField(slot: FooterSlot, field: FooterField) {
        store.edit { it[slot.key] = field.id }
    }

    suspend fun setColumnMode(mode: ColumnMode) {
        store.edit { it[Keys.COLUMN_MODE] = mode.id }
    }

    suspend fun setAutoScrollSpeed(step: Float) {
        store.edit { it[Keys.AUTO_SCROLL_SPEED] = AutoScrollPreference.snap(step) }
    }

    suspend fun setTextAlign(align: ReaderTextAlign) {
        store.edit { it[Keys.TEXT_ALIGN] = align.id }
    }

    suspend fun setFontWeight(weight: ReaderFontWeight) {
        store.edit { it[Keys.FONT_WEIGHT] = weight.id }
    }

    suspend fun setHyphens(value: Boolean?) {
        store.edit {
            if (value == null) it.remove(Keys.HYPHENS) else it[Keys.HYPHENS] = value
        }
    }

    suspend fun setLetterSpacing(value: Double?) {
        setNullableDouble(Keys.LETTER_SPACING, TypographyRange.LETTER_SPACING.sanitize(value))
    }

    suspend fun setWordSpacing(value: Double?) {
        setNullableDouble(Keys.WORD_SPACING, TypographyRange.WORD_SPACING.sanitize(value))
    }

    suspend fun setParagraphSpacing(value: Double?) {
        setNullableDouble(Keys.PARAGRAPH_SPACING, TypographyRange.PARAGRAPH_SPACING.sanitize(value))
    }

    /**
     * Writing a spacing, where "no value" is a key that is not there
     * rather than a sentinel number: an absent key is what every reader
     * of this store already understands as the default, and a sentinel
     * would be one more number that has to be told apart from a real
     * one.
     */
    /** Which stored key an edge of the footer is kept under. */
    private val FooterSlot.key: Preferences.Key<String>
        get() = when (this) {
            FooterSlot.LEFT -> Keys.FOOTER_LEFT
            FooterSlot.RIGHT -> Keys.FOOTER_RIGHT
        }

    private suspend fun setNullableDouble(key: Preferences.Key<Double>, value: Double?) {
        store.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }
}

/**
 * The stored page turn, reading a store written before it had three
 * answers.
 *
 * [stored] wins whenever it is there, including when it names something
 * this version does not know — [PageTurnStyle.fromId] answers that with
 * the default, and falling through to the old boolean instead would let
 * a setting from a newer version be quietly rewritten by a much older
 * one. [legacyAnimation] only ever said yes or no, and no meant the
 * instant jump.
 */
internal fun pageTurnStyleFrom(stored: String?, legacyAnimation: Boolean?): PageTurnStyle = when {
    stored != null -> PageTurnStyle.fromId(stored)
    legacyAnimation == false -> PageTurnStyle.NONE
    else -> PageTurnStyle.Default
}
