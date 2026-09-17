package com.chmouel.liseur.data.settings

import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.reader.annotations.HighlightTint
import kotlinx.coroutines.flow.first

/**
 * What a setting with no value looks like on the wire.
 *
 * Six of the typography settings treat null as a real answer — "use
 * whatever the publisher asked for" — which is a different state from
 * never having been set. The server has no delete and no null, so
 * absence has to travel as a value of its own. Without one, unsetting a
 * setting could never be pushed, and worse, the snapshot would still
 * hold the old number and put it back on the next pass.
 */
const val SETTING_UNSET = "__unset__"

/**
 * One setting that travels between devices.
 *
 * The server stores opaque strings keyed by [key]; this type carries the
 * two directions of the translation between the string on the wire and
 * the typed value in the DataStore.
 *
 * [write] returns whether the string was understood. That answer is not
 * decoration: every `fromId` in this app falls back to the default for
 * an id it does not recognise, so a device running an older build that
 * silently accepted a newer build's font would store the fallback, read
 * back a different value, and push its own default over the choice that
 * was made — then keep doing it, in both directions, forever. A `false`
 * means the value is left alone and nothing is recorded as agreed, so
 * the newer device's answer stays the answer.
 *
 * [affectsOpenBook] marks a setting that re-lays out a book already on
 * screen, and those are held back until the reader closes it. Most of
 * them are the `reader.` typography keys, which is the default, but the
 * wire key is not what decides it: `app.scroll_mode` keys the navigator
 * itself, so applying it under an open book rebuilds the page the
 * reader is looking at.
 */
class SyncableSetting(
    val key: String,
    val read: suspend () -> String,
    val write: suspend (String) -> Boolean,
    val affectsOpenBook: Boolean = key.startsWith("reader."),
)

/**
 * The settings that roam across devices when a liseur-sync account is
 * connected.
 *
 * Each entry names a wire key (`reader.font`, `app.scroll_mode`, …) and
 * the two lambdas that read and write the local value as a string. The
 * server never interprets the value; both sides agree only on the key.
 *
 * Settings not listed here stay on the device, and the line is drawn at
 * anything that is about *this* device rather than about the reader:
 * hardware-dependent choices (e-ink mode, brightness, tap zones, whether
 * the volume keys turn pages), anything that follows the screen
 * (columns), anything about power (keeping the screen on), and UI state
 * (library sort, stats range).
 *
 * The two dictionary settings are a deliberate exception, weighed rather
 * than overlooked: a server that sent both could turn lookups on and
 * point them at a host of its choosing, so they travel only because the
 * reader's own server is the one being trusted.
 */
fun syncableSettings(
    app: AppSettingsRepository,
    reader: ReaderPreferencesRepository,
): List<SyncableSetting> = listOf(
    SyncableSetting(
        key = "reader.font",
        read = { reader.prefs.first().font.id },
        write = { raw -> writeById(raw, ReadingFont::fromId, { it.id }) { reader.setFont(it) } },
    ),
    SyncableSetting(
        key = "reader.font_size",
        read = { reader.prefs.first().fontSize.toString() },
        write = { raw ->
            writeNumber(raw, TypographyRange.FONT_SIZE, nullable = false) {
                reader.setFontSize(it!!)
            }
        },
    ),
    SyncableSetting(
        key = "reader.theme",
        read = { reader.prefs.first().themeChoice.id },
        write = { raw ->
            writeById(raw, ReaderThemeChoice::fromId, { it.id }) { reader.setTheme(it) }
        },
    ),
    SyncableSetting(
        key = "reader.line_height",
        read = { reader.prefs.first().lineHeight.orUnset() },
        write = { raw ->
            writeNumber(raw, TypographyRange.LINE_HEIGHT, nullable = true) {
                reader.setLineHeight(it)
            }
        },
    ),
    SyncableSetting(
        key = "reader.page_margins",
        read = { reader.prefs.first().pageMargins.orUnset() },
        write = { raw ->
            writeNumber(raw, TypographyRange.PAGE_MARGINS, nullable = true) {
                reader.setPageMargins(it)
            }
        },
    ),
    SyncableSetting(
        key = "reader.page_turn_style",
        read = { reader.prefs.first().pageTurnStyle.id },
        write = { raw ->
            writeById(raw, PageTurnStyle::fromId, { it.id }) { reader.setPageTurnStyle(it) }
        },
    ),
    SyncableSetting(
        key = "reader.footer_mode",
        read = { reader.prefs.first().footerMode.id },
        write = { raw ->
            writeById(raw, FooterMode::fromId, { it.id }) { reader.setFooterMode(it) }
        },
    ),
    SyncableSetting(
        key = "reader.text_align",
        read = { reader.prefs.first().textAlign.id },
        write = { raw ->
            writeById(raw, ReaderTextAlign::fromId, { it.id }) { reader.setTextAlign(it) }
        },
    ),
    SyncableSetting(
        key = "reader.font_weight",
        read = { reader.prefs.first().fontWeight.id },
        write = { raw ->
            writeById(raw, ReaderFontWeight::fromId, { it.id }) { reader.setFontWeight(it) }
        },
    ),
    SyncableSetting(
        key = "reader.hyphens",
        read = { reader.prefs.first().hyphens.orUnset() },
        write = { raw -> writeNullableBoolean(raw) { reader.setHyphens(it) } },
    ),
    SyncableSetting(
        key = "reader.letter_spacing",
        read = { reader.prefs.first().letterSpacing.orUnset() },
        write = { raw ->
            writeNumber(raw, TypographyRange.LETTER_SPACING, nullable = true) {
                reader.setLetterSpacing(it)
            }
        },
    ),
    SyncableSetting(
        key = "reader.word_spacing",
        read = { reader.prefs.first().wordSpacing.orUnset() },
        write = { raw ->
            writeNumber(raw, TypographyRange.WORD_SPACING, nullable = true) {
                reader.setWordSpacing(it)
            }
        },
    ),
    SyncableSetting(
        key = "reader.paragraph_spacing",
        read = { reader.prefs.first().paragraphSpacing.orUnset() },
        write = { raw ->
            writeNumber(raw, TypographyRange.PARAGRAPH_SPACING, nullable = true) {
                reader.setParagraphSpacing(it)
            }
        },
    ),
    SyncableSetting(
        key = "reader.auto_scroll_speed",
        read = { reader.prefs.first().autoScrollSpeed.toString() },
        write = { raw ->
            val v = raw.toFloatOrNull()
            val ok = v != null && v.isFinite() &&
                v >= AutoScrollPreference.MIN_STEP && v <= AutoScrollPreference.MAX_STEP
            if (ok) reader.setAutoScrollSpeed(v)
            ok
        },
    ),
    SyncableSetting(
        key = "app.scroll_mode",
        read = { app.current().scrollMode.toString() },
        write = { raw -> writeBoolean(raw) { app.setScrollMode(it) } },
        // Not a `reader.` key, but it decides how the navigator is
        // built, so switching it under an open book rebuilds the page
        // being read.
        affectsOpenBook = true,
    ),
    SyncableSetting(
        key = "app.resume_last_book",
        read = { app.current().resumeLastBook.toString() },
        write = { raw -> writeBoolean(raw) { app.setResumeLastBook(it) } },
    ),
    SyncableSetting(
        key = "app.dictionary_enabled",
        read = { app.current().dictionaryLookupEnabled.toString() },
        write = { raw -> writeBoolean(raw) { app.setDictionaryLookupEnabled(it) } },
    ),
    SyncableSetting(
        key = "app.dictionary_base_url",
        read = { app.current().dictionaryBaseUrl },
        write = { raw ->
            // Asked before writing, not after. setDictionaryBaseUrl puts
            // the default back for anything it refuses, so writing first
            // would destroy this device's URL on the way to reporting
            // that the server's was no good.
            if (DictionaryUrl.normalise(raw) != raw) {
                false
            } else {
                app.setDictionaryBaseUrl(raw)
                true
            }
        },
    ),
    SyncableSetting(
        key = "app.definition_target",
        read = { app.current().definitionTarget.id },
        write = { raw ->
            writeById(raw, DefinitionTarget::fromId, { it.id }) { app.setDefinitionTarget(it) }
        },
    ),
    SyncableSetting(
        key = "app.highlight_tints",
        // The stored set, not HighlightPalette.offered: that resolves an
        // absent set to the default three, so a reader who never chose
        // would be indistinguishable from one who chose exactly those,
        // and "never chose" would travel as a choice and win.
        read = {
            app.offeredHighlightTintNames()?.sorted()?.joinToString(",") ?: SETTING_UNSET
        },
        write = { raw ->
            if (raw == SETTING_UNSET) {
                app.setOfferedHighlightTints(null)
                true
            } else {
                val names = raw.split(",").filter { it.isNotBlank() }
                val known = names.mapNotNull { n ->
                    HighlightTint.entries.firstOrNull { it.name == n }
                }
                if (known.size != names.size) {
                    false
                } else {
                    // Written whole rather than toggled colour by
                    // colour: a toggle per swatch publishes every
                    // palette on the way, and one of those is the empty
                    // set, which means something else entirely.
                    app.setOfferedHighlightTints(known.map { it.name }.toSet())
                    true
                }
            }
        },
    ),
    SyncableSetting(
        key = "app.highlight_tint_default",
        read = { app.current().highlightPalette.default.name },
        write = { raw ->
            val tint = HighlightTint.entries.firstOrNull { it.name == raw }
            if (tint != null) app.setHighlightDefaultTint(tint)
            tint != null
        },
    ),
)

private fun Double?.orUnset(): String = this?.toString() ?: SETTING_UNSET

private fun Boolean?.orUnset(): String = this?.toString() ?: SETTING_UNSET

/**
 * Writes a value named by an id, refusing one this build does not know.
 *
 * Recognition is decided by round-tripping rather than by a second copy
 * of each `fromId`'s table: a parse that gives back the same id
 * understood it, and one that fell through to the default gives back the
 * default's id instead.
 */
private suspend fun <T> writeById(
    raw: String,
    fromId: (String) -> T,
    idOf: (T) -> String,
    set: suspend (T) -> Unit,
): Boolean {
    val parsed = fromId(raw)
    if (idOf(parsed) != raw) return false
    set(parsed)
    return true
}

/**
 * Writes a number, refusing one outside the range it belongs to.
 *
 * Refusing matters because the setters express "out of range" and "not
 * set" the same way — `sanitize` returns null for both — so passing a
 * bad number straight through would quietly clear the setting, and the
 * reader would have to notice and set it again by hand.
 */
private suspend fun writeNumber(
    raw: String,
    range: TypographyRange,
    nullable: Boolean,
    set: suspend (Double?) -> Unit,
): Boolean {
    if (raw == SETTING_UNSET) {
        if (!nullable) return false
        set(null)
        return true
    }
    val v = raw.toDoubleOrNull() ?: return false
    if (!v.isFinite() || v < range.min || v > range.max) return false
    set(v)
    return true
}

private suspend fun writeBoolean(raw: String, set: suspend (Boolean) -> Unit): Boolean {
    val v = raw.toBooleanStrictOrNull() ?: return false
    set(v)
    return true
}

private suspend fun writeNullableBoolean(raw: String, set: suspend (Boolean?) -> Unit): Boolean {
    if (raw == SETTING_UNSET) {
        set(null)
        return true
    }
    val v = raw.toBooleanStrictOrNull() ?: return false
    set(v)
    return true
}
