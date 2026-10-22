package dev.lukas.glyphstatus.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Last fetch of a plugin, kept so the matrix can show it right away. */
data class PluginState(
    val values: Map<String, Any?>,
    val error: String?,
    val history: Map<String, List<Double>>,
    val fetchedAtMillis: Long,
) {
    fun toData() = PluginData(values, error != null, history)
}

/** Installed plugins, which of them rotate, their secrets (encrypted) and their last state. */
class PluginStore(private val context: Context) {

    /** Plugin list and enabled flags; the toy listens to this to pick up changes. */
    val prefs: SharedPreferences = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val states = context.getSharedPreferences("plugin_states", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "plugin_secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** All installed plugins (entries that no longer parse are skipped). */
    fun plugins(): List<Plugin> {
        val array = JSONArray(prefs.getString(KEY_LIST, "[]"))
        return (0 until array.length()).mapNotNull { runCatching { PluginParser.parse(array.getString(it)) }.getOrNull() }
    }

    fun enabled(): List<Plugin> {
        val ids = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
        return plugins().filter { it.id in ids }
    }

    /** Installs [plugin], replacing one with the same id, and enables it. */
    fun add(plugin: Plugin) {
        val others = plugins().filter { it.id != plugin.id }
        val array = JSONArray()
        (others + plugin).forEach { array.put(it.json) }
        val ids = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty() + plugin.id
        prefs.edit().putString(KEY_LIST, array.toString()).putStringSet(KEY_ENABLED, ids).apply()
        states.edit().remove(plugin.id).apply()
    }

    fun remove(id: String) {
        val array = JSONArray()
        plugins().filter { it.id != id }.forEach { array.put(it.json) }
        val ids = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty() - id
        prefs.edit().putString(KEY_LIST, array.toString()).putStringSet(KEY_ENABLED, ids).apply()
        states.edit().remove(id).apply()
        val editor = secretPrefs.edit()
        secretPrefs.all.keys.filter { it.startsWith("$id:") }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun isEnabled(id: String) = id in prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()

    fun setEnabled(id: String, enabled: Boolean) {
        val ids = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_ENABLED, if (enabled) ids + id else ids - id).apply()
    }

    fun secrets(plugin: Plugin): Map<String, String> =
        plugin.secrets.associate { it.key to secretPrefs.getString("${plugin.id}:${it.key}", "").orEmpty() }

    fun setSecret(plugin: Plugin, key: String, value: String) {
        secretPrefs.edit().putString("${plugin.id}:$key", value).apply()
        // Bump a counter so the running toy notices and fetches again.
        prefs.edit().putLong(KEY_SECRETS_CHANGED, System.currentTimeMillis()).apply()
    }

    fun state(id: String): PluginState? {
        val json = states.getString(id, null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val values = obj.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { fromJson(v.opt(it)) } }
            val history = obj.getJSONObject("history").let { h ->
                h.keys().asSequence().associateWith { k -> h.getJSONArray(k).let { a -> (0 until a.length()).map(a::getDouble) } }
            }
            PluginState(values, obj.optString("error").ifEmpty { null }, history, obj.getLong("fetchedAt"))
        }.getOrNull()
    }

    fun saveState(id: String, state: PluginState) {
        val values = JSONObject()
        state.values.forEach { (k, v) -> values.put(k, toJson(v)) }
        val history = JSONObject()
        state.history.forEach { (k, v) -> history.put(k, JSONArray(v)) }
        val obj = JSONObject()
            .put("values", values)
            .put("history", history)
            .put("error", state.error.orEmpty())
            .put("fetchedAt", state.fetchedAtMillis)
        states.edit().putString(id, obj.toString()).apply()
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray(value)
        else -> value
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        is JSONArray -> (0 until value.length()).map(value::getDouble)
        else -> PluginParser.normalize(value)
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_ENABLED = "enabled"
        const val KEY_SECRETS_CHANGED = "secrets_changed"
    }
}
