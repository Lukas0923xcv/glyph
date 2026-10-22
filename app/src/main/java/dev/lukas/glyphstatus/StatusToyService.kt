package dev.lukas.glyphstatus

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.text.format.DateFormat
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class StatusToyService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var checkJob: Job? = null

    private var glyphMatrixManager: GlyphMatrixManager? = null
    private var connected = false
    private var matrixLength = 25

    /** null = never checked. */
    private var status: CachedStatus? = null
    private var displayStore: DisplaySettingsStore? = null
    private var display = DisplaySettings.DEFAULT
    private var tick = 0
    private var lastFrame: IntArray? = null

    private val toyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    if (GlyphToy.EVENT_AOD == event) refreshStatus()
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val serviceMessenger = Messenger(toyHandler)

    private val gmmCallback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            val gmm = glyphMatrixManager ?: return
            gmm.register(if (Common.is25111p()) Glyph.DEVICE_25111p else Glyph.DEVICE_23112)
            connected = true
            lastFrame = null
            render()
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            connected = false
        }
    }

    /** Picks up view/interval changes made in the app while the toy is running. */
    private val displayListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val before = Screens.fetches(display)
        displayStore?.let { display = it.load() }
        // A newly enabled view or design may need data the last check did not fetch.
        if (!before.containsAll(Screens.fetches(display))) refreshStatus(force = true)
        render()
    }

    /** Drives blinking (every tick) and the view rotation (every [DisplaySettings.intervalSeconds]). */
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            render()
            mainHandler.postDelayed(this, Screens.TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        matrixLength = Common.getDeviceMatrixLength().takeIf { it == 13 || it == 25 } ?: 25
        status = StatusCache(this).load()
        displayStore = DisplaySettingsStore(this).also {
            display = it.load()
            it.prefs.registerOnSharedPreferenceChangeListener(displayListener)
        }
        tick = 0
        lastFrame = null

        glyphMatrixManager = GlyphMatrixManager.getInstance(applicationContext).also {
            it.init(gmmCallback)
        }
        mainHandler.postDelayed(ticker, Screens.TICK_MS)
        refreshStatus()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.removeCallbacks(ticker)
        displayStore?.prefs?.unregisterOnSharedPreferenceChangeListener(displayListener)
        displayStore = null
        scope?.cancel()
        scope = null
        checkJob = null
        glyphMatrixManager?.turnOff()
        glyphMatrixManager?.unInit()
        glyphMatrixManager = null
        connected = false
        return false
    }

    /** [force] also re-fetches disk health, which is otherwise only refreshed every 30 min. */
    private fun refreshStatus(force: Boolean = false) {
        val scope = scope ?: return
        if (checkJob?.isActive == true) return
        val previous = status
        val now = System.currentTimeMillis()
        val healthDue = force || previous?.healthOk == null ||
            now - previous.healthCheckedAtMillis >= HEALTH_INTERVAL_MS
        // SMART checks spin up and query every disk, so they are not repeated every minute.
        val wanted = Screens.fetches(display)
        val fetches = if (healthDue) wanted else wanted - Screens.Fetch.HEALTH
        // Keep the last health result between the 30-min checks, but drop it once nobody needs it.
        val keptHealth = if (Screens.Fetch.HEALTH in wanted) previous?.healthOk else null
        checkJob = scope.launch {
            val (result, host) = withContext(Dispatchers.IO) {
                val config = runCatching { ConfigStore(applicationContext).load() }.getOrNull()
                if (config == null) {
                    CheckResult(false, "Einstellungen nicht lesbar") to ""
                } else {
                    ProxmoxClient.check(config, fetches) to config.host
                }
            }
            Log.d(TAG, "Status: up=${result.up} (${result.message})")
            val fetchedHealth = Screens.Fetch.HEALTH in fetches && result.up
            val newStatus = CachedStatus(
                up = result.up,
                uptimeSeconds = result.uptimeSeconds,
                guestsRunning = result.guestsRunning,
                guestsTotal = result.guestsTotal,
                cpuPercent = result.cpuPercent,
                memPercent = result.memPercent,
                diskPercent = result.diskPercent,
                loadAverage = result.loadAverage,
                netMbit = result.netMbit,
                backupOk = result.backupOk,
                checkedAtMillis = System.currentTimeMillis(),
                guestStates = result.guestStates,
                cpuHistory = result.cpuHistory,
                memHistory = result.memHistory,
                updates = result.updates,
                healthOk = if (fetchedHealth) result.healthOk else keptHealth,
                healthCheckedAtMillis = if (fetchedHealth) now else previous?.healthCheckedAtMillis ?: 0,
                quorate = result.quorate,
                downReason = if (result.connectionFailed) Connectivity.downReason(applicationContext, host)
                else Screens.DownReason.SERVER,
            )
            StatusCache(applicationContext).store(newStatus)
            status = newStatus
            render()
        }
    }

    private fun render() {
        val gmm = glyphMatrixManager ?: return
        if (!connected) return
        val now = Calendar.getInstance()
        val frame = Screens.compose(
            length = matrixLength,
            status = status,
            settings = display,
            tick = tick,
            nowMillis = now.timeInMillis,
            hour = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(this),
        )
        // Only push to the matrix when the picture actually changes.
        if (lastFrame?.contentEquals(frame) == true) return
        try {
            gmm.setMatrixFrame(frame)
            lastFrame = frame
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    private companion object {
        const val TAG = "StatusToyService"
        const val HEALTH_INTERVAL_MS = 30 * 60 * 1000L
    }
}
