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
    /** Wall-clock time of the check, to let the uptime keep counting. */
    val checkedAtMillis: Long,
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
            checkedAtMillis = prefs.getLong(KEY_CHECKED_AT, 0),
        )
    }

    fun store(status: CachedStatus) {
        prefs.edit()
            .putBoolean(KEY_UP, status.up)
            .putLong(KEY_UPTIME, status.uptimeSeconds ?: -1)
            .putInt(KEY_GUESTS_RUNNING, status.guestsRunning ?: -1)
            .putInt(KEY_GUESTS_TOTAL, status.guestsTotal ?: -1)
            .putLong(KEY_CHECKED_AT, status.checkedAtMillis)
            .apply()
    }

    private companion object {
        const val KEY_UP = "up"
        const val KEY_UPTIME = "uptime_seconds"
        const val KEY_GUESTS_RUNNING = "guests_running"
        const val KEY_GUESTS_TOTAL = "guests_total"
        const val KEY_CHECKED_AT = "checked_at"
    }
}
