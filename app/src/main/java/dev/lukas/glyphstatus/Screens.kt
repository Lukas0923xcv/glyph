package dev.lukas.glyphstatus

import kotlin.math.roundToInt

/**
 * What the matrix shows. While the server is UP (or not checked yet) the toy rotates
 * through the selected views; while it is DOWN a full-screen sign replaces all of them.
 */
object Screens {

    /** Blink half-period of the DOWN cross and locked guests; also the rotation timer's resolution. */
    const val TICK_MS = 500L

    /** In rotation order. */
    enum class View { UPTIME, GUESTS, CPU, MEM, LOAD, DISK, NET, BACKUP, UPDATES, HEALTH, QUORUM, CLOCK }

    enum class Design { TEXT, RING, HISTORY, DOTS }

    enum class AlertMode {
        OFF,
        /** While a value is over its threshold, only the alerting views rotate. */
        ONLY,
        /** Alerting views are shown after every regular view. */
        PRIORITY,
    }

    enum class DownReason {
        /** The server did not answer or reported a problem: blinking cross. */
        SERVER,
        /** The phone has no network at all. */
        NO_NETWORK,
        /** The host needs Tailscale but no VPN is up on the phone. */
        VPN_DOWN,
    }

    /** A plugin as the rotation sees it; [render] returns null while it has nothing to show. */
    class PluginDisplay(
        val id: String,
        val alert: Boolean,
        val render: (length: Int, blinkOn: Boolean) -> IntArray?,
    )

    /** One entry of the rotation: a built-in view or a plugin. */
    sealed interface Slot {
        data class Builtin(val view: View) : Slot
        data class Extra(val plugin: PluginDisplay) : Slot
    }

    /** Data that needs requests beyond /nodes. */
    enum class Fetch { RESOURCES, LOAD, RRD, TASKS, UPDATES, HEALTH, QUORUM }

    /** Which extra requests the current settings need. */
    fun fetches(settings: DisplaySettings): Set<Fetch> = buildSet {
        val views = settings.views.toMutableSet()
        // Alerts need their data even when the view itself is not in the rotation.
        if (settings.alertMode != AlertMode.OFF) views += listOf(View.DISK, View.BACKUP, View.HEALTH, View.QUORUM)
        for (view in views) {
            when (view) {
                View.GUESTS, View.DISK -> add(Fetch.RESOURCES)
                View.LOAD -> add(Fetch.LOAD)
                View.NET -> add(Fetch.RRD)
                View.CPU, View.MEM -> if (settings.design(view) == Design.HISTORY) add(Fetch.RRD)
                View.BACKUP -> add(Fetch.TASKS)
                View.UPDATES -> add(Fetch.UPDATES)
                View.HEALTH -> add(Fetch.HEALTH)
                View.QUORUM -> add(Fetch.QUORUM)
                View.UPTIME, View.CLOCK -> Unit
            }
        }
    }

    fun hasData(view: View, status: CachedStatus?): Boolean {
        if (view == View.CLOCK) return true
        if (status?.up != true) return false
        return when (view) {
            View.UPTIME -> status.uptimeSeconds != null
            View.GUESTS -> status.guestsRunning != null && status.guestsTotal != null
            View.CPU -> status.cpuPercent != null
            View.MEM -> status.memPercent != null
            View.LOAD -> status.loadAverage != null
            View.DISK -> status.diskPercent != null
            View.NET -> status.netMbit != null
            View.BACKUP -> status.backupOk != null
            View.UPDATES -> status.updates != null
            View.HEALTH -> status.healthOk != null
            View.QUORUM -> status.quorate != null
            View.CLOCK -> true
        }
    }

    /**
     * The [enabled] views that have data to show, in rotation order.
     * Falls back to the clock so the matrix is never empty.
     */
    fun views(status: CachedStatus?, enabled: Set<View>): List<View> =
        View.entries.filter { it in enabled && hasData(it, status) }.ifEmpty { listOf(View.CLOCK) }

    /** Views whose value is critical right now (only with alerts switched on). */
    fun alerts(status: CachedStatus?, settings: DisplaySettings): List<View> {
        if (settings.alertMode == AlertMode.OFF || status?.up != true) return emptyList()
        return buildList {
            if ((status.cpuPercent ?: -1) >= settings.cpuAlert) add(View.CPU)
            if ((status.memPercent ?: -1) >= settings.memAlert) add(View.MEM)
            if ((status.diskPercent ?: -1) >= settings.diskAlert) add(View.DISK)
            if (status.backupOk == false) add(View.BACKUP)
            if (status.healthOk == false) add(View.HEALTH)
            if (status.quorate == false) add(View.QUORUM)
        }
    }

    /** The rotation of built-in views only, taking the alert mode into account. */
    fun playlist(status: CachedStatus?, settings: DisplaySettings): List<View> =
        slots(status, settings, emptyList(), 13).map { (it as Slot.Builtin).view }

    /**
     * The rotation including plugins: built-in views, then plugins that have something to show,
     * then the clock. Alerts (built-in and from plugins) are handled like in [playlist].
     */
    fun slots(status: CachedStatus?, settings: DisplaySettings, plugins: List<PluginDisplay>, length: Int): List<Slot> {
        val available = plugins.filter { it.render(length, true) != null }
        val builtin = View.entries.filter { it != View.CLOCK && it in settings.views && hasData(it, status) }
        val regular = builtin.map { Slot.Builtin(it) } +
            available.map { Slot.Extra(it) } +
            listOfNotNull(Slot.Builtin(View.CLOCK).takeIf { View.CLOCK in settings.views })
        val rotation = regular.ifEmpty { listOf(Slot.Builtin(View.CLOCK)) }
        if (settings.alertMode == AlertMode.OFF) return rotation
        val alerts = alerts(status, settings).map { Slot.Builtin(it) } +
            available.filter { it.alert }.map { Slot.Extra(it) }
        if (alerts.isEmpty()) return rotation
        return when (settings.alertMode) {
            AlertMode.OFF -> rotation
            AlertMode.ONLY -> alerts
            AlertMode.PRIORITY -> buildList {
                for (i in 0 until maxOf(rotation.size, alerts.size)) {
                    add(rotation[i % rotation.size])
                    add(alerts[i % alerts.size])
                }
            }
        }
    }

    fun isNight(settings: DisplaySettings, hour: Int): Boolean {
        if (!settings.nightEnabled || settings.nightFrom == settings.nightTo) return false
        return if (settings.nightFrom < settings.nightTo) {
            hour >= settings.nightFrom && hour < settings.nightTo
        } else {
            hour >= settings.nightFrom || hour < settings.nightTo
        }
    }

    /** The complete picture for timer tick [tick], dimmed to the chosen overall brightness. */
    fun compose(
        length: Int,
        status: CachedStatus?,
        settings: DisplaySettings,
        tick: Int,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
        plugins: List<PluginDisplay> = emptyList(),
    ): IntArray {
        val frame = picture(length, status, settings, tick, nowMillis, hour, minute, is24Hour, plugins)
        val brightness = settings.brightness.coerceIn(MIN_BRIGHTNESS, 100)
        return if (brightness == 100) frame else MatrixGraphics.scale(frame, brightness / 100.0)
    }

    const val MIN_BRIGHTNESS = 10

    private fun picture(
        length: Int,
        status: CachedStatus?,
        settings: DisplaySettings,
        tick: Int,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
        plugins: List<PluginDisplay>,
    ): IntArray {
        val blinkOn = tick % 2 == 0
        if (status?.up == false) return down(length, blinkOn, status.downReason)
        if (isNight(settings, hour)) {
            // Only a failing disk may wake the matrix at night (DOWN is handled above).
            return if (status?.healthOk == false) {
                view(length, View.HEALTH, status, settings, nowMillis, hour, minute, is24Hour, blinkOn)
            } else {
                IntArray(length * length)
            }
        }
        if (settings.clockOnly) return view(length, View.CLOCK, status, settings, nowMillis, hour, minute, is24Hour, blinkOn)
        val slots = slots(status, settings, plugins, length)
        val ticksPerView = (settings.intervalSeconds * 1000 / TICK_MS).toInt().coerceAtLeast(1)
        return when (val slot = slots[(tick / ticksPerView) % slots.size]) {
            is Slot.Builtin -> view(length, slot.view, status, settings, nowMillis, hour, minute, is24Hour, blinkOn)
            is Slot.Extra -> slot.plugin.render(length, blinkOn) ?: IntArray(length * length)
        }
    }

    fun down(length: Int, blinkOn: Boolean, reason: DownReason = DownReason.SERVER): IntArray = when (reason) {
        DownReason.SERVER ->
            if (blinkOn) MatrixIcons.toFrame(MatrixIcons.cross(length), MatrixIcons.BRIGHTNESS_DOWN)
            else IntArray(length * length)
        DownReason.NO_NETWORK -> MatrixGraphics.noNetwork(length, MatrixText.BRIGHTNESS_SYMBOL)
        DownReason.VPN_DOWN -> MatrixText.render(length, listOf(listOf("VPN"))) ?: IntArray(length * length)
    }

    fun view(
        length: Int,
        view: View,
        status: CachedStatus?,
        settings: DisplaySettings,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
        blinkOn: Boolean = true,
    ): IntArray {
        val blank = IntArray(length * length)
        return when (view) {
            View.UPTIME -> MatrixText.render(length, uptimeCandidates(status, nowMillis))
            View.GUESTS -> guests(length, status, settings.design(View.GUESTS), blinkOn)
            View.CPU -> percent(length, 'C', status?.cpuPercent, status?.cpuHistory.orEmpty(), settings, View.CPU)
            View.MEM -> percent(length, 'R', status?.memPercent, status?.memHistory.orEmpty(), settings, View.MEM)
            View.DISK -> percent(length, 'D', status?.diskPercent, emptyList(), settings, View.DISK)
            View.LOAD -> MatrixText.render(length, loadCandidates(status?.loadAverage))
            View.NET -> status?.netMbit?.let {
                MatrixText.render(length, listOf(listOf("N$it"), listOf("N${it.coerceAtMost(99)}")))
            }
            View.BACKUP -> status?.backupOk?.let { MatrixText.render(length, listOf(listOf(if (it) "B✓" else "B✗"))) }
            View.UPDATES -> status?.updates?.let {
                val text = if (it == 0) "U✓" else "U$it"
                MatrixText.render(length, listOf(listOf(text), listOf("U${it.coerceAtMost(99)}")))
            }
            View.HEALTH -> status?.healthOk?.let { MatrixText.render(length, listOf(listOf(if (it) "HD✓" else "HD✗"))) }
            View.QUORUM -> status?.quorate?.let { MatrixText.render(length, listOf(listOf(if (it) "Q✓" else "Q✗"))) }
            View.CLOCK -> {
                val h = if (is24Hour) hour.toString().padStart(2, '0') else (if (hour % 12 == 0) 12 else hour % 12).toString()
                val m = minute.toString().padStart(2, '0')
                MatrixText.render(length, listOf(listOf("$h:$m"), listOf(h, m)))
            }
        } ?: blank
    }

    /** CPU / RAM / disk as text ("C35"), ring around the value, or text over a history chart. */
    private fun percent(
        length: Int,
        label: Char,
        percent: Int?,
        history: List<Int>,
        settings: DisplaySettings,
        view: View,
    ): IntArray? {
        if (percent == null) return null
        val candidates = listOf("$label$percent", "$label${percent.coerceAtMost(99)}")
        val frame = when (settings.design(view)) {
            Design.RING -> {
                // The label letter only if it keeps a gap to the ring (25x25); on 13x13 just the number.
                val ring = MatrixGraphics.ring(length, percent, MatrixText.BRIGHTNESS_SYMBOL)
                val labelled = candidates.map { listOf(it) }
                val numberOnly = listOf(listOf("$percent"))
                val text = MatrixText.render(length, labelled)?.takeIf { MatrixGraphics.keepsGapToRing(length, it) }
                    ?: MatrixText.render(length, numberOnly)
                    ?: return null
                MatrixGraphics.combine(ring, text)
            }
            Design.HISTORY -> {
                val layout = MatrixGraphics.historyLayout(length)
                val text = candidates.firstNotNullOfOrNull { MatrixText.renderLineAt(length, it, layout.textY) }
                    ?: return null
                val bars = MatrixGraphics.historyBars(length, history.ifEmpty { listOf(percent) }, MatrixText.BRIGHTNESS_SYMBOL)
                MatrixGraphics.combine(bars, text)
            }
            else -> MatrixText.render(length, candidates.map { listOf(it) }) ?: return null
        }
        if (!settings.brightnessByLoad) return frame
        val factor = when {
            percent < 50 -> 0.4
            percent < 80 -> 0.7
            else -> 1.0
        }
        return MatrixGraphics.scale(frame, factor)
    }

    /**
     * TEXT: "7/9" on one line if it fits, otherwise running over total as a fraction ("12" over "15").
     * DOTS: one dot per guest; falls back to TEXT when there are too many.
     */
    private fun guests(length: Int, status: CachedStatus?, design: Design, blinkOn: Boolean): IntArray? {
        val running = status?.guestsRunning ?: return null
        val total = status.guestsTotal ?: return null
        if (design == Design.DOTS) {
            status.guestStates?.let { states -> MatrixGraphics.dots(length, states, blinkOn)?.let { return it } }
        }
        return MatrixText.render(length, listOf(listOf("$running/$total")))
            ?: MatrixText.renderFraction(length, "$running", "$total")
            ?: MatrixText.render(length, listOf(listOf("$running")))
    }

    /** "L0.5" below 10 (one decimal), "L12" from 10 on. Formatted by hand to stay locale independent. */
    private fun loadCandidates(load: Double?): List<List<String>> {
        if (load == null) return emptyList()
        val tenths = (load * 10).roundToInt().coerceAtLeast(0)
        val whole = (tenths / 10).coerceAtMost(99)
        return if (tenths < 100) {
            listOf(listOf("L${tenths / 10}.${tenths % 10}"), listOf("L$whole"))
        } else {
            listOf(listOf("L$whole"))
        }
    }

    private fun uptimeCandidates(status: CachedStatus?, nowMillis: Long): List<List<String>> {
        val base = status?.uptimeSeconds ?: return emptyList()
        val elapsed = ((nowMillis - status.checkedAtMillis) / 1000).coerceAtLeast(0)
        val seconds = base + elapsed
        val days = seconds / 86_400
        return if (days >= 1) {
            listOf(listOf("${days}d"), listOf("$days"))
        } else {
            val hours = seconds / 3_600
            listOf(listOf("${hours}h"), listOf("$hours"))
        }
    }
}
