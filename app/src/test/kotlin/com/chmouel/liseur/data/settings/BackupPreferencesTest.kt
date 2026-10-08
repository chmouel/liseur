package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPreferencesTest {

    private val types = mapOf("count" to BackupValueType.INT)

    @Test
    fun `an int setting survives a backup`() {
        val json = preferencesOf(intPreferencesKey("count") to 3).backupJson(types.keys)
        val restored = mutablePreferencesOf()
        JSONObject(json.toString()).applyBackupJson(restored, types)
        assertEquals(3, restored[intPreferencesKey("count")])
    }

    @Test
    fun `an int setting that is not a whole int rejects the backup`() {
        listOf<Any>(2.5, "3", 1e12).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                JSONObject().put("count", value).validateBackupJson(types)
            }
        }
    }
}
