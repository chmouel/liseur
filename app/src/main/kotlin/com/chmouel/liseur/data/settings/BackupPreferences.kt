package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class BackupValueType { STRING, BOOLEAN, DOUBLE, FLOAT, INT, STRING_SET }

internal fun Preferences.backupJson(allowlist: Set<String>): JSONObject = JSONObject().apply {
    asMap().forEach { (key, value) ->
        if (key.name !in allowlist) return@forEach
        when (value) {
            is String -> put(key.name, value)
            is Boolean -> put(key.name, value)
            is Double -> put(key.name, value)
            is Float -> put(key.name, value.toDouble())
            is Int -> put(key.name, value)
            is Set<*> -> put(key.name, JSONArray(value.filterIsInstance<String>().sorted()))
        }
    }
}

/** Unknown optional keys are ignored. Known keys with the wrong JSON type reject the archive. */
internal fun JSONObject.applyBackupJson(
    destination: MutablePreferences,
    known: Map<String, BackupValueType>,
) {
    decodeBackupValues(known).forEach { (name, value) ->
        val type = known.getValue(name)
        @Suppress("UNCHECKED_CAST")
        destination[name.asPreferencesKey(type)] = value
    }
}

internal fun JSONObject.validateBackupJson(known: Map<String, BackupValueType>) {
    decodeBackupValues(known)
}

private fun JSONObject.decodeBackupValues(known: Map<String, BackupValueType>): Map<String, Any> {
    val values = linkedMapOf<String, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val name = keys.next()
        val type = known[name] ?: continue
        val raw = get(name)
        val value: Any = when (type) {
            BackupValueType.STRING -> raw as? String ?: malformed(name)
            BackupValueType.BOOLEAN -> raw as? Boolean ?: malformed(name)
            BackupValueType.DOUBLE -> (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: malformed(name)
            BackupValueType.FLOAT -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: malformed(name)
            BackupValueType.INT -> (raw as? Number)?.toDouble()
                ?.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
                ?: malformed(name)
            BackupValueType.STRING_SET -> {
                val array = raw as? JSONArray ?: malformed(name)
                buildSet {
                    for (i in 0 until array.length()) add(array.get(i) as? String ?: malformed(name))
                }
            }
        }
        values[name] = value
    }
    return values
}

private fun malformed(name: String): Nothing =
    throw IllegalArgumentException("Malformed backup setting: $name")

@Suppress("UNCHECKED_CAST")
private fun String.asPreferencesKey(type: BackupValueType): Preferences.Key<Any> = when (type) {
    BackupValueType.STRING -> androidx.datastore.preferences.core.stringPreferencesKey(this)
    BackupValueType.BOOLEAN -> androidx.datastore.preferences.core.booleanPreferencesKey(this)
    BackupValueType.DOUBLE -> androidx.datastore.preferences.core.doublePreferencesKey(this)
    BackupValueType.FLOAT -> androidx.datastore.preferences.core.floatPreferencesKey(this)
    BackupValueType.INT -> androidx.datastore.preferences.core.intPreferencesKey(this)
    BackupValueType.STRING_SET -> androidx.datastore.preferences.core.stringSetPreferencesKey(this)
} as Preferences.Key<Any>
