package com.chmouel.liseur.translate

/** What the translation sheet does before anything is asked. */
internal sealed interface TranslationStep {
    data object TooLong : TranslationStep
    data object NotSetUp : TranslationStep

    /** The book does not say, and the service cannot tell. */
    data object NeedsSource : TranslationStep
    data object Same : TranslationStep
    data object NotDownloaded : TranslationStep
    data object Unsupported : TranslationStep

    /** Nothing stands in the way: ask the service. */
    data object Translate : TranslationStep

    companion object {
        /**
         * The step for [passage] from [source] (null when unknown) into
         * [target], with a service that is [configured], [detects] the
         * language or not, and offers [targets] from that source (null
         * when it takes any).
         */
        fun of(
            passage: String,
            configured: Boolean,
            detects: Boolean,
            source: String?,
            target: String,
            targets: Map<String, PairState>?,
        ): TranslationStep = when {
            passage.length > TranslationPrompt.MAX_CHARACTERS -> TooLong
            !configured -> NotSetUp
            source != null && TranslationLanguages.same(source, target) -> Same
            source == null && !detects -> NeedsSource
            targets == null -> Translate
            else -> when (targets.entries.filter { TranslationLanguages.same(it.key, target) }.minOfOrNull { it.value }) {
                PairState.Ready -> Translate
                PairState.NeedsDownload -> NotDownloaded
                PairState.Unsupported, null -> Unsupported
            }
        }
    }
}
