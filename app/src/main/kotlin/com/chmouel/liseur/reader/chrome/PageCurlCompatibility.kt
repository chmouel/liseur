package com.chmouel.liseur.reader.chrome

import java.util.Locale

private val DEVICE_NAME_SEPARATORS = Regex("[^a-z0-9]+")

/**
 * Whether the custom page curl should be used on this device.
 */
internal fun isPageCurlSupportedOnDevice(
    manufacturer: String,
    brand: String,
    model: String,
    device: String,
): Boolean {
    val names = listOf(manufacturer, brand, model, device)
        .map { it.lowercase(Locale.ROOT).replace(DEVICE_NAME_SEPARATORS, " ").trim() }

    val isTecnoKj5 =
        names.any { it.hasToken("tecno") } &&
            names.any { it == "kj5" || it.endsWith(" kj5") }
    val isAir1Ultra = names.any { it == "air1 ultra" || it.endsWith(" air1 ultra") }

    return !(isTecnoKj5 || isAir1Ultra)
}

private fun String.hasToken(token: String): Boolean =
    split(' ').any { it == token }
