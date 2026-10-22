package dev.lukas.glyphstatus

/**
 * What the matrix shows. While the server is UP (or not checked yet) the toy rotates
 * through [views]; while it is DOWN a blinking cross replaces all of them.
 */
object Screens {

    enum class View { UPTIME, GUESTS, CLOCK }

    /** Views that have data to show, in rotation order. */
    fun views(status: CachedStatus?): List<View> = buildList {
        if (status?.up == true && status.uptimeSeconds != null) add(View.UPTIME)
        if (status?.up == true && status.guestsRunning != null && status.guestsTotal != null) add(View.GUESTS)
        add(View.CLOCK)
    }

    fun down(length: Int, blinkOn: Boolean): IntArray =
        if (blinkOn) {
            MatrixIcons.toFrame(MatrixIcons.cross(length), MatrixIcons.BRIGHTNESS_DOWN)
        } else {
            IntArray(length * length)
        }

    fun view(
        length: Int,
        view: View,
        status: CachedStatus?,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
    ): IntArray {
        val candidates = when (view) {
            View.UPTIME -> uptimeCandidates(status, nowMillis)
            View.GUESTS -> {
                val running = status?.guestsRunning
                val total = status?.guestsTotal
                if (running == null || total == null) emptyList()
                else listOf(listOf("$running/$total"), listOf("$running"))
            }
            View.CLOCK -> {
                val h = if (is24Hour) hour.toString().padStart(2, '0') else (if (hour % 12 == 0) 12 else hour % 12).toString()
                val m = minute.toString().padStart(2, '0')
                listOf(listOf("$h:$m"), listOf(h, m))
            }
        }
        return MatrixText.render(length, candidates) ?: IntArray(length * length)
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
