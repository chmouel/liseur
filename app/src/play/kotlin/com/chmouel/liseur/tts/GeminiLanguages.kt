package com.chmouel.liseur.tts

/**
 * The languages Gemini's speech models read, as Google lists them for each
 * model; every voice speaks all of them, the model finding the text's
 * language itself.
 */
internal object GeminiLanguages {
    private val FLASH_LITE = setOf(
        "ace", "af", "ak", "am", "ar", "arz", "as", "awa", "az", "ban", "be", "bg", "bho", "bjn", "bn", "bs",
        "bug", "ca", "ceb", "ckb", "cs", "da", "de", "el", "en", "es", "et", "eu", "fa", "ff", "fil", "fr", "gl",
        "gu", "ha", "he", "hi", "hne", "hr", "ht", "hu", "hy", "id", "ilo", "is", "it", "ja", "jv", "ka", "kam",
        "kg", "ki", "kk", "km", "kn", "ko", "ks", "ky", "lg", "ln", "lo", "lus", "lv", "mag", "mai", "min", "mk",
        "ml", "mn", "mni", "mr", "ms", "mt", "nb", "ne", "nl", "nn", "no", "nso", "ny", "or", "pa", "pl", "ps",
        "pt", "ro", "ru", "rw", "sat", "si", "sk", "sr", "ta", "te", "tl", "tr", "uz", "vi", "yue", "zh",
    )

    private val FLASH = FLASH_LITE + setOf(
        "ba", "bem", "crh", "dyu", "dz", "fi", "gn", "ig", "kab", "lb", "lt", "ltg", "my", "oc", "pag", "sd",
        "sl", "so", "sq", "ss", "st", "sv", "sw", "tg", "th", "ti", "ug",
    )

    private val MODELS = mapOf(
        GeminiTts.DEFAULT_MODEL to FLASH_LITE,
        "gemini-3.8-flash-tts" to FLASH,
    )

    /** What [model] reads, or null for a model this build does not know. */
    fun of(model: String): Set<String>? = MODELS[model.trim().removePrefix("models/")]
}
