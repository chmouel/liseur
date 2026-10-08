package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.VoicePreference

/**
 * What a service's voices belong to, so a voice remembered for one engine,
 * server or model is never offered to another: [provider] is the service's
 * id, [context] the device engine's package or a server's API root, and
 * [model] the speech model, blank where there is none.
 */
internal data class VoiceScope(val provider: String, val context: String, val model: String) {
    fun owns(preference: VoicePreference): Boolean =
        preference.provider == provider && preference.context == context && preference.model == model

    /** Remembers [voice] for the language of [language], a tag such as `en-GB`. */
    fun preference(language: String, voice: String): VoicePreference =
        VoicePreference(provider, context, model, SpeechLanguage.primary(language), voice)
}

/**
 * One voice a service offers. [languages] are the normalized tags it speaks,
 * or null when the service does not say, which leaves it for the reader to
 * assign to a language.
 */
internal data class CatalogueVoice(val id: String, val languages: Set<String>?) {
    fun speaks(language: String): Boolean {
        val primary = SpeechLanguage.primary(language)
        return languages?.any { SpeechLanguage.primary(it) == primary } == true
    }

    /** The regional tag it speaks [language] with, such as `en-GB`; null when it names no region. */
    fun accent(language: String): String? {
        val primary = SpeechLanguage.primary(language)
        return languages?.filter { SpeechLanguage.primary(it) == primary }
            ?.singleOrNull()
            ?.takeIf { SpeechLanguage.region(it) != null }
    }

    /** Whether it speaks [tag]'s language with [tag]'s own region. */
    fun speaksExactly(tag: String): Boolean {
        val region = SpeechLanguage.region(tag) ?: return false
        return languages?.any {
            SpeechLanguage.primary(it) == SpeechLanguage.primary(tag) && SpeechLanguage.region(it) == region
        } == true
    }
}

/**
 * The voices a service offers right now, in its own order and narrowed by
 * any "Choose voices" filter: [default] is the service's own default voice,
 * [global] the one saved in its settings, and [failed] says the list could
 * not be fetched, so [voices] are only those typed or remembered.
 */
internal data class VoiceCatalogue(
    val scope: VoiceScope,
    val voices: List<CatalogueVoice>,
    val default: String? = null,
    val global: String? = null,
    val failed: Boolean = false,
)

/** A voice and the language tag a book is read in with it. */
internal data class VoicePick(val voice: String, val language: String)

/** Why reading aloud asks for a language and voice before it starts. */
internal enum class ChoiceReason {
    /** The book declares no language. */
    Missing,

    /** The book declares several. */
    Ambiguous,

    /** None of the service's voices speaks the book's language. */
    NoVoice,

    /** The service's voices could not be listed, and none saved speaks it. */
    CatalogueFailed,
}

internal sealed interface VoiceResolution {
    data class Resolved(val pick: VoicePick) : VoiceResolution
    data class NeedsChoice(val reason: ChoiceReason) : VoiceResolution
}

/**
 * Picks the voice a book is read in, from its declared language and the
 * voices remembered for each language. Never changes service: what the
 * service in use cannot read is asked about instead.
 */
internal object VoiceResolver {
    fun resolve(book: BookLanguage, catalogue: VoiceCatalogue, preferences: List<VoicePreference>): VoiceResolution {
        val tag = when (book) {
            BookLanguage.Missing -> return VoiceResolution.NeedsChoice(ChoiceReason.Missing)
            is BookLanguage.Ambiguous -> return VoiceResolution.NeedsChoice(ChoiceReason.Ambiguous)
            is BookLanguage.Known -> book.tag
        }
        val voice = voiceFor(tag, catalogue, preferences)
            ?: return VoiceResolution.NeedsChoice(if (catalogue.failed) ChoiceReason.CatalogueFailed else ChoiceReason.NoVoice)
        return VoiceResolution.Resolved(VoicePick(voice, tag))
    }

    /**
     * The voice for [language]: the one remembered for it, else the saved
     * voice when it speaks it, else one with the exact region, else one
     * in the same language; among several, the service's default first.
     */
    fun voiceFor(language: String, catalogue: VoiceCatalogue, preferences: List<VoicePreference>): String? {
        remembered(language, catalogue, preferences)?.let { return it }
        catalogue.voices.firstOrNull { it.id == catalogue.global && it.speaks(language) }?.let { return it.id }
        val speaking = catalogue.voices.filter { it.speaks(language) }
        return preferred(speaking.filter { it.speaksExactly(language) }, catalogue.default)
            ?: preferred(speaking, catalogue.default)
    }

    /**
     * The voice remembered for [language] in this catalogue's scope, while
     * it is still offered and not known to speak another language.
     */
    fun remembered(language: String, catalogue: VoiceCatalogue, preferences: List<VoicePreference>): String? {
        val primary = SpeechLanguage.primary(language)
        val voice = preferences.lastOrNull { catalogue.scope.owns(it) && it.language == primary }?.voice ?: return null
        return voice.takeIf { id -> catalogue.voices.any { it.id == id && (it.languages == null || it.speaks(language)) } }
    }

    private fun preferred(voices: List<CatalogueVoice>, default: String?): String? =
        (voices.firstOrNull { it.id == default } ?: voices.firstOrNull())?.id

    /**
     * The language a voice picked in Settings is remembered for: the one
     * its [languages] name, when they name one; otherwise the [session]'s,
     * when the voice may speak it. Null leaves only the saved voice set.
     */
    fun settingsLanguage(languages: Set<String>?, session: String?): String? {
        val primaries = languages?.map(SpeechLanguage::primary)?.distinct()
        if (primaries?.size == 1) return primaries.single()
        val language = session?.let(SpeechLanguage::primary) ?: return null
        return language.takeIf { primaries == null || it in primaries }
    }

    /** The languages the voice sheet offers: those of the book, the session, the voices, what was remembered, and the device's. */
    fun languages(
        catalogue: VoiceCatalogue,
        preferences: List<VoicePreference>,
        book: BookLanguage,
        session: String?,
        device: String?,
    ): List<String> {
        val bookTags = when (book) {
            is BookLanguage.Known -> listOf(book.tag)
            is BookLanguage.Ambiguous -> book.tags
            BookLanguage.Missing -> emptyList()
        }
        return (
            bookTags + listOfNotNull(session, device) +
                catalogue.voices.flatMap { it.languages.orEmpty() } +
                preferences.filter(catalogue.scope::owns).map { it.language }
            ).map(SpeechLanguage::primary).distinct()
    }

    /**
     * The tag a book is read in once [language] is chosen for it: the
     * book's own when it declares that language, else the [voice]'s region
     * for it, else the language alone.
     */
    fun sessionTag(language: String, book: BookLanguage, voice: CatalogueVoice?): String {
        val primary = SpeechLanguage.primary(language)
        val declared = when (book) {
            is BookLanguage.Known -> listOf(book.tag)
            is BookLanguage.Ambiguous -> book.tags
            BookLanguage.Missing -> emptyList()
        }
        return declared.firstOrNull { SpeechLanguage.primary(it) == primary }
            ?: voice?.accent(primary)
            ?: primary
    }
}
