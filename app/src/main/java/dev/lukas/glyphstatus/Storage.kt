package dev.lukas.glyphstatus

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ServerConfig(
    val host: String,
    val tokenId: String,
    val secret: String,
    val fingerprint: String,
)

/** Connection settings, stored encrypted (Android Keystore-backed master key). */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load() = ServerConfig(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        tokenId = prefs.getString(KEY_TOKEN_ID, "").orEmpty(),
        secret = prefs.getString(KEY_SECRET, "").orEmpty(),
        fingerprint = prefs.getString(KEY_FINGERPRINT, "").orEmpty(),
    )

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putString(KEY_TOKEN_ID, config.tokenId)
            .putString(KEY_SECRET, config.secret)
            .putString(KEY_FINGERPRINT, config.fingerprint)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "server_config"
        const val KEY_HOST = "host"
        const val KEY_TOKEN_ID = "token_id"
        const val KEY_SECRET = "secret"
        const val KEY_FINGERPRINT = "fingerprint"
    }
}

/** Last known status, so the matrix can show something immediately when the toy starts. */
data class CachedStatus(
    val up: Boolean,
    val uptimeSeconds: Long?,
    val guestsRunning: Int?,
    val guestsTotal: Int?,
    val cpuPercent: Int?,
    val memPercent: Int?,
    val diskPercent: Int?,
    val loadAverage: Double?,
    val netMbit: Int?,
    val backupOk: Boolean?,
    /** Wall-clock time of the check, to let the uptime keep counting. */
    val checkedAtMillis: Long,
    /** One char per VM/LXC, ordered by VMID: 'r' running, 's' stopped, 'l' locked. */
    val guestStates: String? = null,
    /** CPU / RAM usage of the last minutes, oldest first, 0..100. */
    val cpuHistory: List<Int> = emptyList(),
    val memHistory: List<Int> = emptyList(),
    val updates: Int? = null,
    val healthOk: Boolean? = null,
    /** When disk health was last fetched; it is only refreshed every 30 min. */
    val healthCheckedAtMillis: Long = 0,
    val quorate: Boolean? = null,
    /** Why the server counts as down (only meaningful while [up] is false). */
    val downReason: Screens.DownReason = Screens.DownReason.SERVER,
)

class StatusCache(context: Context) {

    private val prefs = context.getSharedPreferences("status_cache", Context.MODE_PRIVATE)

    /** null = never checked. */
    fun load(): CachedStatus? {
        if (!prefs.contains(KEY_UP)) return null
        return CachedStatus(
            up = prefs.getBoolean(KEY_UP, false),
            uptimeSeconds = prefs.getLong(KEY_UPTIME, -1).takeIf { it >= 0 },
            guestsRunning = prefs.getInt(KEY_GUESTS_RUNNING, -1).takeIf { it >= 0 },
            guestsTotal = prefs.getInt(KEY_GUESTS_TOTAL, -1).takeIf { it >= 0 },
            cpuPercent = prefs.getInt(KEY_CPU, -1).takeIf { it >= 0 },
            memPercent = prefs.getInt(KEY_MEM, -1).takeIf { it >= 0 },
            diskPercent = prefs.getInt(KEY_DISK, -1).takeIf { it >= 0 },
            loadAverage = prefs.getFloat(KEY_LOAD, -1f).takeIf { it >= 0 }?.toDouble(),
            netMbit = prefs.getInt(KEY_NET, -1).takeIf { it >= 0 },
            backupOk = getTriState(KEY_BACKUP),
            checkedAtMillis = prefs.getLong(KEY_CHECKED_AT, 0),
            guestStates = prefs.getString(KEY_GUEST_STATES, null),
            cpuHistory = getIntList(KEY_CPU_HISTORY),
            memHistory = getIntList(KEY_MEM_HISTORY),
            updates = prefs.getInt(KEY_UPDATES, -1).takeIf { it >= 0 },
            healthOk = getTriState(KEY_HEALTH),
            healthCheckedAtMillis = prefs.getLong(KEY_HEALTH_CHECKED_AT, 0),
            quorate = getTriState(KEY_QUORATE),
            downReason = Screens.DownReason.entries.getOrElse(prefs.getInt(KEY_DOWN_REASON, 0)) { Screens.DownReason.SERVER },
        )
    }

    fun store(status: CachedStatus) {
        prefs.edit()
            .putBoolean(KEY_UP, status.up)
            .putLong(KEY_UPTIME, status.uptimeSeconds ?: -1)
            .putInt(KEY_GUESTS_RUNNING, status.guestsRunning ?: -1)
            .putInt(KEY_GUESTS_TOTAL, status.guestsTotal ?: -1)
            .putInt(KEY_CPU, status.cpuPercent ?: -1)
            .putInt(KEY_MEM, status.memPercent ?: -1)
            .putInt(KEY_DISK, status.diskPercent ?: -1)
            .putFloat(KEY_LOAD, status.loadAverage?.toFloat() ?: -1f)
            .putInt(KEY_NET, status.netMbit ?: -1)
            .putInt(KEY_BACKUP, triState(status.backupOk))
            .putLong(KEY_CHECKED_AT, status.checkedAtMillis)
            .putString(KEY_GUEST_STATES, status.guestStates)
            .putString(KEY_CPU_HISTORY, status.cpuHistory.joinToString(","))
            .putString(KEY_MEM_HISTORY, status.memHistory.joinToString(","))
            .putInt(KEY_UPDATES, status.updates ?: -1)
            .putInt(KEY_HEALTH, triState(status.healthOk))
            .putLong(KEY_HEALTH_CHECKED_AT, status.healthCheckedAtMillis)
            .putInt(KEY_QUORATE, triState(status.quorate))
            .putInt(KEY_DOWN_REASON, status.downReason.ordinal)
            .apply()
    }

    private fun triState(value: Boolean?) = when (value) { true -> 1; false -> 0; null -> -1 }

    private fun getTriState(key: String) = when (prefs.getInt(key, -1)) { 1 -> true; 0 -> false; else -> null }

    private fun getIntList(key: String) =
        prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private companion object {
        const val KEY_UP = "up"
        const val KEY_UPTIME = "uptime_seconds"
        const val KEY_GUESTS_RUNNING = "guests_running"
        const val KEY_GUESTS_TOTAL = "guests_total"
        const val KEY_CPU = "cpu_percent"
        const val KEY_MEM = "mem_percent"
        const val KEY_DISK = "disk_percent"
        const val KEY_LOAD = "load_average"
        const val KEY_NET = "net_mbit"
        const val KEY_BACKUP = "backup_ok"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_GUEST_STATES = "guest_states"
        const val KEY_CPU_HISTORY = "cpu_history"
        const val KEY_MEM_HISTORY = "mem_history"
        const val KEY_UPDATES = "updates"
        const val KEY_HEALTH = "health_ok"
        const val KEY_HEALTH_CHECKED_AT = "health_checked_at"
        const val KEY_QUORATE = "quorate"
        const val KEY_DOWN_REASON = "down_reason"
    }
}

/** Everything the user chooses under "Anzeige auf der Matrix". */
data class DisplaySettings(
    val views: Set<Screens.View>,
    val intervalSeconds: Int,
    val designs: Map<Screens.View, Screens.Design> = emptyMap(),
    /** Dim CPU/RAM/disk values when the load is low. */
    val brightnessByLoad: Boolean = false,
    val alertMode: Screens.AlertMode = Screens.AlertMode.OFF,
    val cpuAlert: Int = 90,
    val memAlert: Int = 95,
    val diskAlert: Int = 90,
    /** Matrix stays dark from [nightFrom] to [nightTo] (hours) unless something is badly wrong. */
    val nightEnabled: Boolean = false,
    val nightFrom: Int = 23,
    val nightTo: Int = 7,
    /** Overall matrix brightness in percent (10..100), applied to everything. */
    val brightness: Int = 100,
    /** Show only the clock instead of the rotation (DOWN still takes over). */
    val clockOnly: Boolean = false,
) {
    fun design(view: Screens.View): Screens.Design = designs[view] ?: Screens.Design.TEXT

    companion object {
        val INTERVAL_CHOICES = listOf(3, 5, 10, 15, 30, 60)
        val DEFAULT = DisplaySettings(setOf(Screens.View.UPTIME, Screens.View.GUESTS, Screens.View.CLOCK), 5)

        /** Views that offer more than one design, with their choices (first = default). */
        val DESIGN_CHOICES = mapOf(
            Screens.View.GUESTS to listOf(Screens.Design.TEXT, Screens.Design.DOTS),
            Screens.View.CPU to listOf(Screens.Design.TEXT, Screens.Design.RING, Screens.Design.HISTORY),
            Screens.View.MEM to listOf(Screens.Design.TEXT, Screens.Design.RING, Screens.Design.HISTORY),
            Screens.View.DISK to listOf(Screens.Design.TEXT, Screens.Design.RING),
        )
    }
}

class DisplaySettingsStore(context: Context) {

    /** Switches between "clock only" and the rotation; returns the new state. */
    fun toggleClockOnly(): Boolean {
        val settings = load()
        save(settings.copy(clockOnly = !settings.clockOnly))
        return !settings.clockOnly
    }

    val prefs: SharedPreferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun load(): DisplaySettings {
        val default = DisplaySettings.DEFAULT
        val views = prefs.getStringSet(KEY_VIEWS, null)
            ?.mapNotNull { name -> Screens.View.entries.find { it.name == name } }?.toSet()
            ?: default.views
        val designs = DisplaySettings.DESIGN_CHOICES.keys.mapNotNull { view ->
            val name = prefs.getString(KEY_DESIGN_PREFIX + view.name, null) ?: return@mapNotNull null
            Screens.Design.entries.find { it.name == name }?.let { view to it }
        }.toMap()
        return DisplaySettings(
            views = views,
            intervalSeconds = prefs.getInt(KEY_INTERVAL, default.intervalSeconds),
            designs = designs,
            brightnessByLoad = prefs.getBoolean(KEY_BRIGHTNESS_BY_LOAD, default.brightnessByLoad),
            alertMode = Screens.AlertMode.entries.find { it.name == prefs.getString(KEY_ALERT_MODE, null) }
                ?: default.alertMode,
            cpuAlert = prefs.getInt(KEY_CPU_ALERT, default.cpuAlert),
            memAlert = prefs.getInt(KEY_MEM_ALERT, default.memAlert),
            diskAlert = prefs.getInt(KEY_DISK_ALERT, default.diskAlert),
            nightEnabled = prefs.getBoolean(KEY_NIGHT, default.nightEnabled),
            nightFrom = prefs.getInt(KEY_NIGHT_FROM, default.nightFrom),
            nightTo = prefs.getInt(KEY_NIGHT_TO, default.nightTo),
            brightness = prefs.getInt(KEY_BRIGHTNESS, default.brightness),
            clockOnly = prefs.getBoolean(KEY_CLOCK_ONLY, default.clockOnly),
        )
    }

    fun save(settings: DisplaySettings) {
        val editor = prefs.edit()
        for (view in DisplaySettings.DESIGN_CHOICES.keys) {
            editor.putString(KEY_DESIGN_PREFIX + view.name, settings.design(view).name)
        }
        editor
            .putStringSet(KEY_VIEWS, settings.views.map { it.name }.toSet())
            .putInt(KEY_INTERVAL, settings.intervalSeconds)
            .putBoolean(KEY_BRIGHTNESS_BY_LOAD, settings.brightnessByLoad)
            .putString(KEY_ALERT_MODE, settings.alertMode.name)
            .putInt(KEY_CPU_ALERT, settings.cpuAlert)
            .putInt(KEY_MEM_ALERT, settings.memAlert)
            .putInt(KEY_DISK_ALERT, settings.diskAlert)
            .putBoolean(KEY_NIGHT, settings.nightEnabled)
            .putInt(KEY_NIGHT_FROM, settings.nightFrom)
            .putInt(KEY_NIGHT_TO, settings.nightTo)
            .putInt(KEY_BRIGHTNESS, settings.brightness)
            .putBoolean(KEY_CLOCK_ONLY, settings.clockOnly)
            .apply()
    }

    private companion object {
        const val KEY_VIEWS = "views"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_DESIGN_PREFIX = "design_"
        const val KEY_BRIGHTNESS_BY_LOAD = "brightness_by_load"
        const val KEY_ALERT_MODE = "alert_mode"
        const val KEY_CPU_ALERT = "cpu_alert"
        const val KEY_MEM_ALERT = "mem_alert"
        const val KEY_DISK_ALERT = "disk_alert"
        const val KEY_NIGHT = "night_enabled"
        const val KEY_NIGHT_FROM = "night_from"
        const val KEY_NIGHT_TO = "night_to"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_CLOCK_ONLY = "clock_only"
    }
}
