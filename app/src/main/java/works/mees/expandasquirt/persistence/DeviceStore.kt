package works.mees.expandasquirt.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class KnownDevice(
    val mac: String,
    val nickname: String,
    val lastKnownVersion: String? = null,
    val lastSeenEpochMs: Long = 0,
)

@Serializable
data class HotspotCreds(
    val ssid: String,
    val password: String,
)

private val Context.dataStore by preferencesDataStore("devices")

class DeviceStore(private val ctx: Context) {
    private val keyKnown = stringPreferencesKey("known_devices_json")
    private val keyCurrent = stringPreferencesKey("current_mac")
    private val keyHotspot = stringPreferencesKey("hotspot_creds_json")

    val known: Flow<List<KnownDevice>> = ctx.dataStore.data.map { prefs ->
        prefs[keyKnown]?.let { Json.decodeFromString<List<KnownDevice>>(it) } ?: emptyList()
    }

    val currentMac: Flow<String?> = ctx.dataStore.data.map { it[keyCurrent] }

    val hotspot: Flow<HotspotCreds?> = ctx.dataStore.data.map { prefs ->
        prefs[keyHotspot]?.let { Json.decodeFromString<HotspotCreds>(it) }
    }

    suspend fun setCurrent(mac: String) {
        ctx.dataStore.edit { it[keyCurrent] = mac }
    }

    suspend fun saveHotspot(creds: HotspotCreds) {
        ctx.dataStore.edit { it[keyHotspot] = Json.encodeToString(creds) }
    }

    suspend fun clearHotspot() {
        ctx.dataStore.edit { it.remove(keyHotspot) }
    }

    suspend fun upsert(d: KnownDevice) {
        ctx.dataStore.edit { prefs ->
            val list = prefs[keyKnown]?.let {
                Json.decodeFromString<List<KnownDevice>>(it)
            } ?: emptyList()
            val updated = list.filter { it.mac != d.mac } + d
            prefs[keyKnown] = Json.encodeToString<List<KnownDevice>>(updated)
        }
    }

    suspend fun forget(mac: String) {
        ctx.dataStore.edit { prefs ->
            val list = prefs[keyKnown]?.let {
                Json.decodeFromString<List<KnownDevice>>(it)
            } ?: emptyList()
            prefs[keyKnown] = Json.encodeToString<List<KnownDevice>>(
                list.filter { it.mac != mac },
            )
            if (prefs[keyCurrent] == mac) {
                prefs.remove(keyCurrent)
            }
        }
    }
}
