package nl.bartvandermeeren.dudan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.deliveryStore by preferencesDataStore(name = "background_results")

/**
 * Background results the phone already told the user about, kept across restarts so a poll, a
 * reconnect or a restarted app never posts the same notification twice. Keys come from
 * ChatEngine.BackgroundArrival and name the server and the delivery. Keeps the latest [LIMIT].
 */
class DeliveryAcks(private val context: Context) {
    private val key = stringPreferencesKey("announced")

    /** Records [id]. Returns false when it was recorded before. */
    suspend fun record(id: String): Boolean {
        var added = false
        context.deliveryStore.edit { prefs ->
            val (ids, isNew) = add(prefs[key].orEmpty(), id)
            added = isNew
            prefs[key] = ids
        }
        return added
    }

    companion object {
        const val LIMIT = 200

        /** [stored] is one id per line, oldest first. Returns the new list and whether [id] is new. */
        internal fun add(stored: String, id: String, limit: Int = LIMIT): Pair<String, Boolean> {
            val ids = stored.lines().filter { it.isNotEmpty() }
            if (id in ids) return stored to false
            return (ids + id).takeLast(limit).joinToString("\n") to true
        }
    }
}
