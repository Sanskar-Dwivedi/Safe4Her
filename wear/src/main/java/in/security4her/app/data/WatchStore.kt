package `in`.security4her.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import `in`.security4her.app.sos.TwistStrength

private val Context.dataStore by preferencesDataStore(name = "s4h_watch")

/** All persistent watch state. DataStore edits are atomic, which is what makes incident creation deduplicated. */
class WatchStore private constructor(private val context: Context) {

    val incident: Flow<ActiveIncident?> = context.dataStore.data.map { decode(it, KEY_INCIDENT, ActiveIncident.serializer()) }
    val link: Flow<DeviceLink?> = context.dataStore.data.map { decode(it, KEY_LINK, DeviceLink.serializer()) }
    val phoneStatus: Flow<PhoneStatus?> = context.dataStore.data.map { decode(it, KEY_STATUS, PhoneStatus.serializer()) }
    val setupDone: Flow<Boolean> = context.dataStore.data.map { it[KEY_SETUP] ?: false }
    val safetyMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_SAFETY] ?: false }
    /** Voice SOS: listen for "HELP HELP HELP". Its own switch, independent of Safety Mode. */
    val voiceSos: Flow<Boolean> = context.dataStore.data.map { it[KEY_VOICE] ?: false }
    val fakeCallTwist: Flow<Boolean> = context.dataStore.data.map { it[KEY_FAKE_CALL_TWIST] ?: false }
    val twistStrength: Flow<TwistStrength> = context.dataStore.data.map { prefs ->
        TwistStrength.entries.firstOrNull { it.name == prefs[KEY_TWIST_STRENGTH] } ?: TwistStrength.STANDARD
    }

    suspend fun incidentNow(): ActiveIncident? = incident.first()
    suspend fun linkNow(): DeviceLink? = link.first()
    suspend fun setupDoneNow(): Boolean = setupDone.first()
    suspend fun safetyModeNow(): Boolean = safetyMode.first()
    suspend fun fakeCallTwistNow(): Boolean = fakeCallTwist.first()

    /** Stores [fresh] unless a live (not ended) incident exists; returns whichever incident is live afterwards. */
    suspend fun createIncidentIfNone(fresh: ActiveIncident): ActiveIncident {
        var result = fresh
        context.dataStore.edit { p ->
            val current = decode(p, KEY_INCIDENT, ActiveIncident.serializer())
            if (current != null && !current.ended) {
                result = current
            } else {
                p[KEY_INCIDENT] = S4hJson.encodeToString(ActiveIncident.serializer(), fresh)
                p.remove(KEY_STATUS)
                result = fresh
            }
        }
        return result
    }

    /** Applies [change] only if the stored incident still has [incidentId]. */
    suspend fun updateIncident(incidentId: String, change: (ActiveIncident) -> ActiveIncident) {
        context.dataStore.edit { p ->
            val current = decode(p, KEY_INCIDENT, ActiveIncident.serializer())
            if (current != null && current.incidentId == incidentId) {
                p[KEY_INCIDENT] = S4hJson.encodeToString(ActiveIncident.serializer(), change(current))
            }
        }
    }

    suspend fun clearIncident() {
        context.dataStore.edit {
            it.remove(KEY_INCIDENT)
            it.remove(KEY_STATUS)
        }
    }

    suspend fun saveLink(link: DeviceLink) {
        context.dataStore.edit { it[KEY_LINK] = S4hJson.encodeToString(DeviceLink.serializer(), link) }
    }

    suspend fun savePhoneStatus(status: PhoneStatus) {
        context.dataStore.edit { it[KEY_STATUS] = S4hJson.encodeToString(PhoneStatus.serializer(), status) }
    }

    suspend fun setSetupDone(done: Boolean) {
        context.dataStore.edit { it[KEY_SETUP] = done }
    }

    suspend fun setSafetyMode(on: Boolean) {
        context.dataStore.edit { it[KEY_SAFETY] = on }
    }

    suspend fun setVoiceSos(on: Boolean) {
        context.dataStore.edit { it[KEY_VOICE] = on }
    }

    suspend fun setFakeCallTwist(on: Boolean) {
        context.dataStore.edit { it[KEY_FAKE_CALL_TWIST] = on }
    }

    suspend fun setTwistStrength(strength: TwistStrength) {
        context.dataStore.edit { it[KEY_TWIST_STRENGTH] = strength.name }
    }

    private fun <T> decode(p: Preferences, key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        p[key]?.let { runCatching { S4hJson.decodeFromString(serializer, it) }.getOrNull() }

    companion object {
        private val KEY_INCIDENT = stringPreferencesKey("incident")
        private val KEY_LINK = stringPreferencesKey("link")
        private val KEY_STATUS = stringPreferencesKey("phone_status")
        private val KEY_SETUP = booleanPreferencesKey("setup_done")
        private val KEY_SAFETY = booleanPreferencesKey("safety_mode")
        private val KEY_VOICE = booleanPreferencesKey("voice_sos")
        private val KEY_FAKE_CALL_TWIST = booleanPreferencesKey("fake_call_twist")
        private val KEY_TWIST_STRENGTH = stringPreferencesKey("twist_strength")

        @Volatile private var instance: WatchStore? = null

        fun get(context: Context): WatchStore =
            instance ?: synchronized(this) {
                instance ?: WatchStore(context.applicationContext).also { instance = it }
            }
    }
}
