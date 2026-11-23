package com.chmouel.liseur.data.library

import java.util.Locale

internal data class SampleBookSpec(
    val code: String,
    val assetPath: String,
    val targetFileName: String,
)

internal object SampleBooks {
    val SUPPORTED_CODES: Set<String> = setOf(
        "cs",
        "de",
        "en",
        "es",
        "fr",
        "it",
        "pt",
        "ru",
        "zh",
    )

    private const val DEFAULT_CODE = "en"

    fun codeFor(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        return if (language in SUPPORTED_CODES) language else DEFAULT_CODE
    }

    fun specFor(locale: Locale = Locale.getDefault()): SampleBookSpec {
        val code = codeFor(locale)
        return SampleBookSpec(
            code = code,
            assetPath = "samples/$code.epub",
            targetFileName = "sample-$code.epub",
        )
    }
}
