package com.chmouel.liseur.data.settings

import androidx.compose.ui.graphics.Color
import com.chmouel.liseur.data.settings.fonts.UserFont
import kotlin.math.roundToInt

/**
 * Curated reading fonts, all OFL-licensed and bundled in assets/fonts.
 * [cssName] is the family name declared to the Readium navigator;
 * null means the publisher's original font.
 */
enum class ReaderFont(val id: String, val displayName: String, val cssName: String?) {
    LITERATA("literata", "Literata", "Literata"),
    VOLLKORN("vollkorn", "Vollkorn", "Vollkorn"),
    ATKINSON("atkinson", "Atkinson Hyperlegible", "Atkinson Hyperlegible"),
    INTER("inter", "Inter", "Inter"),
    PUBLISHER("publisher", "Publisher font", null),
    ;

    companion object {
        val Default = LITERATA

        fun fromId(id: String?): ReaderFont = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * The font a book is read in: one Liseur ships, or one the reader brought.
 *
 * [ReaderFont] stays the closed list of bundled families it always was.
 * This is the wider question the rest of the app now asks, and the two
 * are deliberately separate — an imported font has no `displayName` worth
 * hard-coding and no asset path, and folding it into the enum would mean
 * every `when` over the bundled four grew a branch that cannot be
 * exhaustive.
 *
 * [cssName] is derivable from the id alone, without consulting the
 * registry of imported fonts, so mapping a preference to Readium's
 * [org.readium.r2.navigator.preferences.FontFamily] needs no lookup and
 * cannot fail.
 */
sealed interface ReadingFont {
    val id: String

    /** The declared family, or null for the publisher's own font. */
    val cssName: String?

    data class Bundled(val font: ReaderFont) : ReadingFont {
        override val id: String get() = font.id
        override val cssName: String? get() = font.cssName
    }

    /**
     * A font the reader imported, named by the SHA-256 of its file.
     *
     * [cssName] is namespaced rather than the family's own name: an
     * imported font is perfectly entitled to call itself Literata, and
     * unprefixed it would silently take over the bundled declaration or
     * a publisher's embedded face.
     */
    data class Imported(val digest: String) : ReadingFont {
        override val id: String get() = UserFont.ID_PREFIX + digest
        override val cssName: String get() = "LiseurUser-$digest"
    }

    /**
     * This font if its file is still there, otherwise the default.
     *
     * The distinction between the *raw* choice and the *effective* one is
     * the whole reason a deleted font is not a lost setting. DataStore and
     * `book_typography` keep the raw value untouched; only this resolved
     * one reaches the navigator, the preview and the rendered selection.
     * Re-import the same file and the digest, the id and every book's
     * choice come back.
     */
    fun effective(registry: Set<String>): ReadingFont =
        if (this is Imported && id !in registry) Default else this

    companion object {
        val Default: ReadingFont = Bundled(ReaderFont.Default)

        /**
         * The font a stored id names, or the default.
         *
         * Bundled ids are matched first, then the reserved `user:`
         * namespace, and **only** in its canonical shape. Anything else —
         * null, blank, corrupt, or an id from a version that has not been
         * written yet — falls back, exactly as [ReaderFont.fromId] already
         * did. An unknown id is never guessed to be an import, so a
         * bundled font added later cannot be mistaken for one.
         */
        fun fromId(id: String?): ReadingFont {
            if (id == null) return Default
            ReaderFont.entries.firstOrNull { it.id == id }?.let { return Bundled(it) }
            return UserFont.digestOf(id)?.let(::Imported) ?: Default
        }
    }
}

/** Reading color themes, Kindle-style: independent from the app theme. */
enum class ReaderTheme(
    val id: String,
    val displayName: String,
    val background: Color,
    val foreground: Color,
) {
    LIGHT("light", "Light", Color(0xFFFFFFFF), Color(0xFF1A1A1A)),
    SEPIA("sepia", "Sepia", Color(0xFFF6EFDF), Color(0xFF3D3229)),
    DARK("dark", "Dark", Color(0xFF1F1F1F), Color(0xFFCECECE)),
    BLACK("black", "Black", Color(0xFF000000), Color(0xFFB8B8B8)),
    ;

    companion object {
        val Default = LIGHT

        fun fromId(id: String?): ReaderTheme = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * The reading theme as it was chosen, which is not always a palette.
 *
 * [ReaderTheme] answers "what colour is the page"; every piece of
 * reading chrome asks it that. This answers "what was asked for", and
 * the two differ in exactly one case: [FOLLOW_APP], which has no
 * colours of its own and takes them from how the app is drawn.
 *
 * That case is the point of the type. Reading themes used to be
 * unreachable from Settings and to default to [ReaderTheme.LIGHT]
 * whatever the app was set to, so turning the app dark left every book
 * white with nothing to say why.
 *
 * The ids are the ones already written to DataStore, so a reader who
 * chose a theme before this existed keeps it, and only a reader who
 * never chose one — who has no stored id at all — lands on
 * [FOLLOW_APP].
 */
enum class ReaderThemeChoice(val id: String, val palette: ReaderTheme?) {
    FOLLOW_APP("follow_app", null),
    LIGHT("light", ReaderTheme.LIGHT),
    SEPIA("sepia", ReaderTheme.SEPIA),
    DARK("dark", ReaderTheme.DARK),
    BLACK("black", ReaderTheme.BLACK),
    ;

    /**
     * The page this choice comes to, given how the app is drawn.
     *
     * [FOLLOW_APP] resolves to [ReaderTheme.DARK] or
     * [ReaderTheme.LIGHT] and never to [ReaderTheme.SEPIA]: sepia is a
     * taste, dark is a lighting condition, and only the second is
     * something asking the app for a dark theme can be read as wanting.
     */
    fun resolve(appIsDark: Boolean): ReaderTheme =
        palette ?: if (appIsDark) ReaderTheme.DARK else ReaderTheme.LIGHT

    companion object {
        val Default = FOLLOW_APP

        fun fromId(id: String?): ReaderThemeChoice =
            entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * What the middle of the reading footer shows.
 *
 * The percentage read and the page number live at the footer's edges
 * and are always drawn; this enum only decides the slot between them,
 * and tapping the footer cycles it.
 */
enum class FooterMode(val id: String) {
    /**
     * Time left in the chapter once a reading pace has actually been
     * measured, and the chapter title until then — so the slot degrades
     * to something true rather than to a stock guess or to nothing.
     */
    SMART("time_chapter"),
    TIME_LEFT_BOOK("time_book"),
    CHAPTER_TITLE("chapter"),
    EMPTY("empty"),
    NONE("none"),
    ;

    /**
     * The next thing to show when the footer is tapped.
     *
     * [NONE] is not in the round: it hides the whole footer, and a tap
     * that landed on it would take away the very thing being tapped.
     * Hiding the footer is a decision, and it belongs in the typography
     * sheet where it can be undone. [EMPTY] is safe in the round — the
     * edges keep drawing, so the footer stays visible and tappable.
     */
    fun next(): FooterMode {
        val cycle = entries.filter { it != NONE }
        val at = cycle.indexOf(this)
        return cycle[(at + 1) % cycle.size]
    }

    companion object {
        val Default = SMART

        /**
         * Reads a stored mode. The ids `page` and `percent` belonged to
         * modes that put a single figure in the single slot the footer
         * used to have; both figures are now permanent edges, so those
         * preferences resolve to the default middle instead.
         */
        fun fromId(id: String?): FooterMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * How many columns of text a page is broken into.
 *
 * [AUTO] leaves the decision to Readium, which turns two columns on by
 * itself once the page is wide enough. The other two say so outright,
 * for the reader who wants one long column on a tablet or two short
 * ones on a smaller screen held sideways.
 *
 * Readium only honours a forced count on a wide enough viewport — its
 * stylesheet keeps the rule behind a `min-width: 60em` media query — so
 * on a phone all three of these read the same, which is the point: the
 * setting can be shown everywhere without changing what a phone does.
 */
enum class ColumnMode(val id: String, val displayName: String) {
    AUTO("auto", "Auto"),
    ONE("one", "1"),
    TWO("two", "2"),
    ;

    companion object {
        val Default = AUTO

        fun fromId(id: String?): ColumnMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Which notch the auto-scroll slider sits on, as it is stored.
 *
 * The bounds live here rather than beside the scrolling loop because
 * they describe the *setting*: what the slider may show, and what the
 * preference store may hold. The pace those notches come to is the
 * reader's business, and is in `reader/chrome/AutoScroll.kt`.
 *
 * A step is stored rather than a speed so the slider means the same
 * thing whatever else changes, and so a reader who comes back to it
 * finds the notch they left it on.
 */
object AutoScrollPreference {

    const val MIN_STEP = 1
    const val MAX_STEP = 10
    const val DEFAULT_STEP = 4f

    /**
     * A step read back from storage, made safe to use.
     *
     * Nothing in the app writes a step outside the range, but the store
     * is a file on a device and this is the only door its contents come
     * through. A value that is not a number at all falls back to the
     * default rather than propagating: a NaN pace multiplies out to a
     * NaN distance, which is a page that silently never moves again.
     */
    fun sanitize(step: Float): Float =
        if (step.isFinite()) {
            step.coerceIn(MIN_STEP.toFloat(), MAX_STEP.toFloat())
        } else {
            DEFAULT_STEP
        }

    /** The nearest whole notch, for a slider that lands on one. */
    fun snap(step: Float): Float = sanitize(step).roundToInt().toFloat()
}

/**
 * How a line of text meets the right-hand edge.
 *
 * [DEFAULT] sends nothing, leaving the book's own alignment alone.
 * Readium collapses `CENTER` and `END` to `start`, so neither is worth
 * offering: the only two distinguishable answers are here.
 *
 * The Readium value stays out of the enum so this package keeps building
 * without the navigator on its classpath; the translation lives in
 * `reader/ReaderPreferencesMapper.kt`.
 */
enum class ReaderTextAlign(val id: String) {
    DEFAULT("default"),
    RAGGED("ragged"),
    JUSTIFIED("justified"),
    ;

    companion object {
        val Default = DEFAULT

        fun fromId(id: String?): ReaderTextAlign = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * How heavy the text is drawn, as a multiplier of the book's own weight.
 *
 * Stored as an id rather than a number so that a value Readium would
 * reject — its `EpubPreferences` requires `0.0..2.5` — can never reach
 * the store in the first place.
 *
 * An override reaches everything that inherits it, headings included, so
 * a book's own emphasis flattens towards the chosen weight. That is the
 * setting working as asked, and why the default is to send nothing.
 */
enum class ReaderFontWeight(val id: String, val multiplier: Double?) {
    DEFAULT("default", null),
    LIGHT("light", 0.75),
    NORMAL("normal", 1.0),
    BOLD("bold", 1.75),
    ;

    companion object {
        val Default = DEFAULT

        fun fromId(id: String?): ReaderFontWeight = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * The bounds of one numeric reading setting, and the single door every
 * value of it comes through.
 *
 * Three kinds of field need three different answers to the same
 * question — what to do with a number that should not be there — and
 * keeping them in one type is what stops the answers drifting apart.
 *
 * The categories differ over one thing: whether an out-of-range value is
 * a preference to honour approximately, or evidence that the stored
 * value is not a preference at all.
 *
 * - [Slider] is dragged, its minimum is zero, and Liseur's range is
 *   deliberately narrower than Readium's. A larger value is a real
 *   preference from a wider range, so it is clamped. Zero is a value:
 *   "no extra spacing".
 * - [Segmented] offers three choices. The reader can only write one of
 *   them or nothing, so anything else is corruption or another build,
 *   and it is discarded rather than clamped — clamping would turn a
 *   stray byte into an override the reader never made, which switches
 *   Readium's advanced styles on and renormalizes the whole book.
 * - [Required] has no null to fall back to, so it falls back to a value.
 */
sealed class TypographyRange(
    val min: Double,
    val max: Double,
) {

    /** A dragged spacing: clamps into range, keeps an explicit zero. */
    class Slider(min: Double, max: Double, val increment: Double) : TypographyRange(min, max) {

        /** How many notches the slider has between [min] and [max]. */
        val tickCount: Int = ((max - min) / increment).roundToInt()

        /**
         * Slider `steps`, which Compose counts as the notches *between*
         * the ends rather than the intervals.
         */
        val steps: Int = (tickCount - 1).coerceAtLeast(0)

        /**
         * The nearest notch, found by counting them rather than by
         * arithmetic on the value.
         *
         * A `Double` cannot hold most of these increments exactly, so
         * adding one repeatedly walks off the notches; multiplying a
         * whole count does not.
         */
        fun snap(value: Double): Double {
            val ticks = ((value - min) / increment).roundToInt().coerceIn(0, tickCount)
            return min + ticks * increment
        }

        /** Which notch [value] sits on, or 0 for a value that is not set. */
        fun tickOf(value: Double?): Int =
            if (value == null) 0 else ((value - min) / increment).roundToInt().coerceIn(0, tickCount)
    }

    /** One of a few offered values: in range or nothing. */
    class Segmented(min: Double, max: Double) : TypographyRange(min, max)

    /** Not nullable, so an unusable value falls back to [fallback]. */
    class Required(min: Double, max: Double, val fallback: Double) : TypographyRange(min, max)

    /**
     * A stored number made safe to hand to Readium.
     *
     * Everything not finite is refused first, because `NaN >= 0` is
     * false and `NaN.coerceIn(a, b)` is still `NaN` — a NaN would
     * therefore survive a clamp and then trip the `require` in
     * `EpubPreferences`, crashing the reader while its settings are
     * being changed.
     */
    fun sanitize(value: Double?): Double? {
        val fallback = (this as? Required)?.fallback
        if (value == null || !value.isFinite() || value < 0.0) return fallback
        return when (this) {
            is Slider -> snap(value.coerceIn(min, max))
            is Segmented -> value.takeIf { it in min..max }
            is Required -> value.coerceIn(min, max)
        }
    }

    /** [sanitize] for a field that must produce a number. */
    fun require(value: Double): Double = sanitize(value) ?: (this as Required).fallback

    companion object {
        /**
         * Readium's own supported ranges, which are wider than the ones
         * Liseur offers. A value inside them is a real preference even
         * when no control here can express it.
         */
        val FONT_SIZE = Required(
            ReaderPrefs.MIN_FONT_SIZE,
            ReaderPrefs.MAX_FONT_SIZE,
            fallback = 1.0,
        )

        /** Readium's `lineHeight` range. Zero is below it, and discarded. */
        val LINE_HEIGHT = Segmented(min = 1.0, max = 2.0)

        /** Readium's `pageMargins` range, whose minimum really is zero. */
        val PAGE_MARGINS = Segmented(min = 0.0, max = 4.0)

        val LETTER_SPACING = Slider(min = 0.0, max = 0.25, increment = 0.01)
        val WORD_SPACING = Slider(min = 0.0, max = 0.5, increment = 0.02)
        val PARAGRAPH_SPACING = Slider(min = 0.0, max = 2.0, increment = 0.1)
    }
}

/**
 * Which of Readium's four stylesheets a book will be rendered with, and
 * so which of these settings it can honour.
 *
 * Readium picks a stylesheet per publication and the variants do not
 * carry the same rules: `bodyHyphens`, `letterSpacing` and `wordSpacing`
 * appear only in the default one, and `textAlign` in the default and
 * right-to-left ones. A control for a rule that is not in the sheet
 * takes a tap and changes nothing.
 *
 * The distinction is *not* "is this book left-to-right". A horizontal
 * Japanese book reads left to right and still gets the CJK sheet.
 *
 * [Unknown] is the settings screen, where no book is open: there the
 * reader is choosing a default for every book they will open, so nothing
 * is disabled and the wording names the writing systems instead.
 */
enum class ReadingCss {
    Default,
    Rtl,
    Cjk,

    /** A fixed-layout book, which honours none of these settings. */
    Unsupported,

    /** No book — the settings screen. */
    Unknown,
    ;

    /** Whether this book's stylesheet has any of these rules at all. */
    val honoursAnything: Boolean get() = this != Unsupported

    /** `textAlign` is in the default and right-to-left stylesheets. */
    val honoursAlignment: Boolean get() = this == Default || this == Rtl || this == Unknown

    /** Hyphens, letter and word spacing are in the default one only. */
    val honoursLatinSpacing: Boolean get() = this == Default || this == Unknown
}

/**
 * Which stylesheet Readium will choose for a book, worked out from the
 * publication alone.
 *
 * This mirrors three pieces of Readium that are `internal` and so cannot
 * be called: `Layout.from`, `EpubSettingsResolver`, and the `isCjk` and
 * `isRtl` extensions. It is exact only because **Liseur sets no
 * `language`, `readingProgression` or `verticalText` preference and no
 * `EpubDefaults`** — every branch of the resolver that could diverge
 * from metadata is unreachable, so the answer is a function of the
 * publication. `ReaderPreferencesMapperTest` pins that invariant.
 *
 * Deriving it from the publication rather than from the navigator's
 * resolved settings is what lets the answer exist before the navigator
 * does. The mapper needs it to decide whether to switch advanced styles
 * on, and classifying late would mean opening the book one way and
 * correcting it a moment later — a reflow, and a reading position that
 * moves while the reader watches.
 *
 * `verticalText` needs no branch of its own: Readium only resolves it
 * true for a CJK language, which is already tested first.
 *
 * @param metadataRtl the publication's declared reading progression, or
 *   null when it declares none and the language has to answer for it.
 */
fun readingCssFor(
    reflowable: Boolean,
    language: String?,
    metadataRtl: Boolean?,
): ReadingCss = when {
    !reflowable -> ReadingCss.Unsupported
    isCjkLanguage(language) -> ReadingCss.Cjk
    metadataRtl ?: isRtlLanguage(language) -> ReadingCss.Rtl
    else -> ReadingCss.Default
}

/**
 * Readium's `Language.isCjk`, quirk included.
 *
 * `ja` and `ko` are compared against the *whole* code, so `ja-JP` and
 * `ko-KR` are not CJK to Readium; only `zh` is compared against the
 * region-stripped code. That is a bug, and mirroring it is deliberate:
 * this decides what the UI tells the reader about the page Readium is
 * actually going to produce, not the page it ought to.
 */
private fun isCjkLanguage(language: String?): Boolean {
    val code = language?.replace("_", "-")?.lowercase() ?: return false
    return code == "ja" || code == "ko" || code.substringBefore("-") == "zh"
}

/**
 * Readium's `Language.isRtl`, which compares the whole code to a fixed
 * list — so `ar-EG` is not right-to-left to it. Mirrored for the same
 * reason as [isCjkLanguage]:
 *
 * ```kotlin
 * internal val Language.isRtl: Boolean get() {
 *     val c = code.lowercase()
 *     return c == "ar" || c == "fa" || c == "he" || c == "zh-hant" || c == "zh-tw"
 * }
 * ```
 *
 * `zh-hant` and `zh-tw` are in that list even though they are Chinese,
 * and they are kept here on purpose. Readium uses this predicate to
 * resolve a book's *reading progression*, and only then picks a
 * stylesheet, where CJK is tested first — so a `zh-Hant` book is
 * right-to-left *and* gets the CJK stylesheet. [readingCssFor] tests CJK
 * first for that reason and never returns [ReadingCss.Rtl] for a `zh-*`
 * book, which the `traditional Chinese is CJK and never RTL` test pins.
 *
 * Pruning the two tags would read better and would make this stop
 * matching Readium: anyone reordering the branches in [readingCssFor]
 * would then get an answer Readium does not give, which is the failure
 * mode the exact mirror exists to prevent.
 */
private fun isRtlLanguage(language: String?): Boolean {
    val code = language?.replace("_", "-")?.lowercase() ?: return false
    return code == "ar" || code == "fa" || code == "he" || code == "zh-hant" || code == "zh-tw"
}

/**
 * User reading preferences.
 *
 * @param fontSize Percentage where 1.0 is the publisher's default size.
 * @param lineHeight Line height multiplier (1.0–2.0), null keeps publisher styles.
 * @param pageMargins Page margin multiplier (0.5–2.0), null keeps publisher styles.
 * @param brightness Screen brightness override 0.0–1.0, null follows the system.
 * @param pageTurnAnimation Slide animation when turning pages; instant jump when off.
 * @param footerMode What the reading footer shows.
 * @param columnMode How many columns of text a wide page is broken into.
 * @param autoScrollSpeed Which notch the auto-scroll slider sits on, from
 *   [AutoScrollPreference.MIN_STEP] to [AutoScrollPreference.MAX_STEP]. Not a
 *   speed: the pace it comes to also depends on [fontSize], because larger
 *   text has further to travel to show the same words.
 * @param textAlign How a line meets the right-hand edge.
 * @param fontWeight How heavy the text is drawn.
 * @param hyphens Whether words break across lines. Null is not "off":
 *   justified text hyphenates by itself, so leaving this alone in a
 *   justified book still gives hyphens. See [justificationHyphenates].
 * @param letterSpacing Extra space between letters, in rem. Readium
 *   halves it on the way to CSS.
 * @param wordSpacing Extra space between words, in rem.
 * @param paragraphSpacing Space between paragraphs, in rem.
 */
data class ReaderPrefs(
    val font: ReadingFont = ReadingFont.Default,
    val fontSize: Double = 1.0,
    val themeChoice: ReaderThemeChoice = ReaderThemeChoice.Default,
    val lineHeight: Double? = null,
    val pageMargins: Double? = null,
    val brightness: Float? = null,
    val pageTurnAnimation: Boolean = true,
    val footerMode: FooterMode = FooterMode.Default,
    val columnMode: ColumnMode = ColumnMode.Default,
    val autoScrollSpeed: Float = AutoScrollPreference.DEFAULT_STEP,
    val textAlign: ReaderTextAlign = ReaderTextAlign.Default,
    val fontWeight: ReaderFontWeight = ReaderFontWeight.Default,
    val hyphens: Boolean? = null,
    val letterSpacing: Double? = null,
    val wordSpacing: Double? = null,
    val paragraphSpacing: Double? = null,
) {
    companion object {
        const val MIN_FONT_SIZE = 0.6
        const val MAX_FONT_SIZE = 2.5
    }
}

/**
 * The same preferences with every number made safe to hand to Readium.
 *
 * `EpubPreferences` throws from its constructor on a negative size,
 * spacing or margin, so an unusable stored value is not a wrong page but
 * a crash — and the moment it would happen is the moment the reader is
 * changing their settings. Every door a number comes through calls this:
 * the preference store, a book's own typography row, and the mapper
 * itself, which is reached by paths that built a [ReaderPrefs] directly.
 */
fun ReaderPrefs.sanitized(): ReaderPrefs = copy(
    fontSize = TypographyRange.FONT_SIZE.require(fontSize),
    lineHeight = TypographyRange.LINE_HEIGHT.sanitize(lineHeight),
    pageMargins = TypographyRange.PAGE_MARGINS.sanitize(pageMargins),
    letterSpacing = TypographyRange.LETTER_SPACING.sanitize(letterSpacing),
    wordSpacing = TypographyRange.WORD_SPACING.sanitize(wordSpacing),
    paragraphSpacing = TypographyRange.PARAGRAPH_SPACING.sanitize(paragraphSpacing),
    brightness = brightness?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
    autoScrollSpeed = AutoScrollPreference.sanitize(autoScrollSpeed),
)

/**
 * Whether Readium's advanced styles have to be switched on for this
 * book — which is to say, whether `publisherStyles` must go false.
 *
 * This is not "has the reader changed anything". Turning advanced styles
 * on does far more than enable the setting that asked for it: it
 * restyles `:root`, every heading, `p`, `li`, `dd`, `div`, `pre`,
 * `small`, `sub` and `sup`, normalizing the book's whole type scale and
 * overriding the sizes its designer chose. That is a price worth paying
 * for a setting the reader asked for and the book can honour, and worth
 * paying for nothing otherwise.
 *
 * So two narrowings, both of which mean *less* interference than before:
 *
 * - `pageMargins`, `fontSize` and `fontWeight` are excluded. Their rules
 *   are not gated on advanced styles at all — Readium's own
 *   `EpubPreferencesEditor` gates their effectiveness on nothing but a
 *   reflowable publication. Page margins used to force it, so a reader
 *   who had only widened their margins lost their book's heading sizes
 *   for no reason.
 * - The rest are counted only when [css] can honour them. An app-wide
 *   letter spacing would otherwise renormalize an Arabic book whose
 *   stylesheet has no letter-spacing rule, applying none of the spacing
 *   that asked for it.
 *
 * A value that is set to zero, and hyphenation switched off, both count:
 * they are things the reader asked for, not the absence of a request.
 * Call it on [sanitized] values, so that a number discarded as
 * unusable cannot switch the whole apparatus on.
 */
fun ReaderPrefs.requiresAdvancedStyles(css: ReadingCss): Boolean {
    if (!css.honoursAnything) return false
    if (lineHeight != null || paragraphSpacing != null) return true
    if (css.honoursAlignment && textAlign != ReaderTextAlign.DEFAULT) return true
    if (!css.honoursLatinSpacing) return false
    return hyphens != null || letterSpacing != null || wordSpacing != null
}

/**
 * Whether this book will be hyphenated without anyone asking for it.
 *
 * Readium's stylesheet carries
 * `:root[style*=readium-advanced-on][style*="--USER__textAlign: justify"] body { hyphens: auto }`,
 * and `--USER__bodyHyphens` is `!important`, so an explicit setting wins
 * and no setting loses. Justified text therefore hyphenates on its own,
 * and the way out is to switch hyphenation *off*, not to leave it at its
 * default. The reader is told so rather than left to discover it.
 */
fun justificationHyphenates(align: ReaderTextAlign, hyphens: Boolean?): Boolean =
    align == ReaderTextAlign.JUSTIFIED && hyphens == null

/**
 * Where a spacing slider's thumb rests, for a value that may not be set.
 *
 * An unset spacing and an explicit zero both put the thumb at the start
 * of the range, which is why the row needs a button as well as a slider:
 * there is no drag from one to the other, and a press that moves nothing
 * is not guaranteed to report itself.
 */
fun spacingThumb(value: Double?, range: TypographyRange.Slider): Float =
    (value ?: range.min).toFloat()

/**
 * What a slider commits, which is never null: dragging a spacing is how
 * a reader says they want one, including when they want none.
 */
fun spacingCommit(thumb: Float, range: TypographyRange.Slider): Double =
    range.snap(thumb.toDouble().coerceIn(range.min, range.max))
