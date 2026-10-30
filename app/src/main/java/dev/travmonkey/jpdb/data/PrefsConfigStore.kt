package dev.travmonkey.jpdb.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Config persisted as one JSON blob in SharedPreferences, readable synchronously from workers too. */
class PrefsConfigStore(context: Context) : ConfigStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val flow = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<Config>(it) }.getOrNull() } ?: Config()
    )
    override val config = flow.asStateFlow()

    @Synchronized
    override fun update(f: (Config) -> Config) {
        val c = flow.updateAndGet(f)
        prefs.edit().putString(KEY, json.encodeToString(c)).apply()
    }

    private companion object {
        const val KEY = "config"
    }
}
