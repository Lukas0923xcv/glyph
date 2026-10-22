package dev.lukas.glyphstatus

import android.content.Context
import org.json.JSONArray

/** Rides out short connection drops (Doze, network switch) instead of flashing DOWN at once. */
object Reliability {

    val THRESHOLD_CHOICES = listOf(1, 2, 3, 5)

    /**
     * The status to show after a check. A failed *connection* only turns an UP server DOWN after
     * [threshold] failures in a row; until then the last good status stays. An answer from the
     * server that says something is wrong (node offline, HTTP error) counts at once.
     */
    fun apply(previous: CachedStatus?, fresh: CachedStatus, connectionFailed: Boolean, threshold: Int): CachedStatus {
        if (fresh.up) return fresh.copy(failures = 0)
        val failures = (previous?.failures ?: 0) + 1
        if (connectionFailed && previous?.up == true && failures < threshold) {
            return previous.copy(failures = failures)
        }
        return fresh.copy(failures = failures)
    }
}

/** The last checks, newest first, so problems like "no network after 30 min" can be seen in the app. */
class CheckLog(context: Context) {

    private val prefs = context.getSharedPreferences("check_log", Context.MODE_PRIVATE)

    fun add(line: String) {
        val old = entries()
        val array = JSONArray()
        (listOf(line) + old).take(MAX_ENTRIES).forEach { array.put(it) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun entries(): List<String> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 50
    }
}
