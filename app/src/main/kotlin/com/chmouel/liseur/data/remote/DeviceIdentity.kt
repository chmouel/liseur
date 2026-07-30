package com.chmouel.liseur.data.remote

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore(name = "device")

/** What a server should call this phone, and how it tells it apart. */
data class DeviceIdentity(val id: String, val name: String)

/**
 * A name for this device, for servers that keep reading positions per
 * device.
 *
 * Komga records who saved each position and shows it in its own
 * interface, so this is a name the reader will see. It is invented here
 * rather than taken from the phone: an advertising id or a hardware
 * serial would identify the person across everything else they use, and
 * a reading position does not need to know who they are, only that they
 * are not their other phone.
 *
 * The model name is used as the label because it is what makes the entry
 * recognisable in a list, and it says nothing that the shape of the
 * device did not already say.
 */
class DeviceIdentityRepository(private val context: Context) {

    suspend fun current(): DeviceIdentity {
        val stored = context.deviceStore.data.map { it[KEY] }.first()
        if (stored != null) return DeviceIdentity(stored, label())

        val fresh = UUID.randomUUID().toString()
        context.deviceStore.edit { it[KEY] = fresh }
        return DeviceIdentity(fresh, label())
    }

    private fun label(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Liseur" }

    private companion object {
        val KEY = stringPreferencesKey("device_id")
    }
}
