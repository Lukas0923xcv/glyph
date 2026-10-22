package dev.lukas.glyphstatus.plugin

import dev.lukas.glyphstatus.CachedStatus
import dev.lukas.glyphstatus.MatrixGraphics
import dev.lukas.glyphstatus.MatrixText
import dev.lukas.glyphstatus.Screens
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** Values a plugin with source type "builtin" can use: everything the app already knows. */
object BuiltinValues {

    val NAMES = setOf(
        "up", "uptime_hours", "uptime_days", "guests_running", "guests_total", "guests_stopped",
        "cpu", "mem", "disk", "load", "net_mbit", "backup_ok", "health_ok", "quorate", "updates",
        "cpu_history", "mem_history",
    )

    /** Extra Proxmox requests needed for each builtin value (values not listed come with /nodes). */
    private val FETCHES = mapOf(
        "guests_running" to Screens.Fetch.RESOURCES,
        "guests_total" to Screens.Fetch.RESOURCES,
        "guests_stopped" to Screens.Fetch.RESOURCES,
        "disk" to Screens.Fetch.RESOURCES,
        "load" to Screens.Fetch.LOAD,
        "net_mbit" to Screens.Fetch.RRD,
        "cpu_history" to Screens.Fetch.RRD,
        "mem_history" to Screens.Fetch.RRD,
        "backup_ok" to Screens.Fetch.TASKS,
        "updates" to Screens.Fetch.UPDATES,
        "health_ok" to Screens.Fetch.HEALTH,
        "quorate" to Screens.Fetch.QUORUM,
    )

    /** The requests a builtin plugin's referenced values need, so it works without the matching built-in view. */
    fun fetchesFor(plugin: Plugin): Set<Screens.Fetch> {
        if (plugin.source != Source.Builtin) return emptySet()
        val used = plugin.variants.flatMap { v ->
            v.conditions.mapNotNull { it.value } + listOfNotNull(v.value) +
                v.text.flatMap { t -> PluginParser.PLACEHOLDER.findAll(t).map { it.groupValues[1] }.toList() }
        }
        return used.mapNotNull { FETCHES[it] }.toSet()
    }

    fun of(status: CachedStatus?, nowMillis: Long): Map<String, Any?> {
        if (status == null) return emptyMap()
        val uptime = status.uptimeSeconds?.let { it + ((nowMillis - status.checkedAtMillis) / 1000).coerceAtLeast(0) }
        return mapOf(
            "up" to status.up,
            "uptime_hours" to uptime?.let { (it / 3_600).toDouble() },
            "uptime_days" to uptime?.let { (it / 86_400).toDouble() },
            "guests_running" to status.guestsRunning?.toDouble(),
            "guests_total" to status.guestsTotal?.toDouble(),
            "guests_stopped" to status.guestsRunning?.let { r -> status.guestsTotal?.let { (it - r).toDouble() } },
            "cpu" to status.cpuPercent?.toDouble(),
            "mem" to status.memPercent?.toDouble(),
            "disk" to status.diskPercent?.toDouble(),
            "load" to status.loadAverage,
            "net_mbit" to status.netMbit?.toDouble(),
            "backup_ok" to status.backupOk,
            "health_ok" to status.healthOk,
            "quorate" to status.quorate,
            "updates" to status.updates?.toDouble(),
            "cpu_history" to status.cpuHistory.map { it.toDouble() }.ifEmpty { null },
            "mem_history" to status.memHistory.map { it.toDouble() }.ifEmpty { null },
        )
    }
}

/** What a plugin knows at a given moment. */
data class PluginData(
    /** Extracted values: Double, String, Boolean or List<Double>; missing values are absent or null. */
    val values: Map<String, Any?>,
    /** Last fetch failed (network, HTTP or JSON error). */
    val error: Boolean,
    /** Recorded samples per numeric value, oldest first (for the history design). */
    val history: Map<String, List<Double>> = emptyMap(),
)

object PluginEngine {

    const val HISTORY_SAMPLES = 24

    /** Reads every declared value from a parsed response (JSON object/array, or the text body). */
    fun extract(plugin: Plugin, body: Any?): Map<String, Any?> =
        plugin.values.mapValues { (_, spec) -> extract(spec, body) }

    fun extract(spec: ValueSpec, body: Any?): Any? {
        val raw: Any? = when (spec.kind) {
            ValueSpec.Kind.PATH -> PluginParser.normalize(resolve(body, spec.path))
            else -> {
                val array = resolve(body, spec.path) as? JSONArray ?: return null
                val items = (0 until array.length()).map { array.opt(it) }.filter { item ->
                    spec.where.all { (field, expected) -> looseEquals(PluginParser.normalize(resolve(item, field)), expected) }
                }
                if (spec.kind == ValueSpec.Kind.COUNT) {
                    items.size.toDouble()
                } else {
                    val numbers = items.mapNotNull { PluginParser.normalize(resolve(it, spec.field.orEmpty())) as? Double }
                    if (numbers.isEmpty()) return null
                    when (spec.kind) {
                        ValueSpec.Kind.SUM -> numbers.sum()
                        ValueSpec.Kind.MAX -> numbers.max()
                        ValueSpec.Kind.MIN -> numbers.min()
                        else -> numbers.average()
                    }
                }
            }
        }
        return if (raw is Double) raw * spec.scale else raw
    }

    /** Follows "a.b[0].c" through JSON objects and arrays; "" or "$" is the root. */
    fun resolve(root: Any?, path: String): Any? {
        if (path.isEmpty() || path == "$") return root
        var current = root
        for (part in path.split('.')) {
            val name = part.substringBefore('[')
            if (name.isNotEmpty()) current = (current as? JSONObject)?.opt(name) ?: return null
            for (index in Regex("\\[(\\d+)]").findAll(part).map { it.groupValues[1].toInt() }) {
                current = (current as? JSONArray)?.opt(index) ?: return null
            }
        }
        return current
    }

    private fun looseEquals(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> a == b
        else -> a?.toString() == b?.toString()
    }

    fun matches(condition: Condition, data: PluginData): Boolean {
        condition.error?.let { return it == data.error }
        val value = data.values[condition.value] ?: return false
        val than = condition.than
        if (value is Double && than is Double) {
            return when (condition.op) {
                "==" -> value == than
                "!=" -> value != than
                ">" -> value > than
                ">=" -> value >= than
                "<" -> value < than
                else -> value <= than
            }
        }
        return when (condition.op) {
            "==" -> looseEquals(value, than)
            "!=" -> !looseEquals(value, than)
            else -> false
        }
    }

    /** First variant whose conditions all hold; a variant without "if" always matches. */
    fun variant(plugin: Plugin, data: PluginData): Variant? =
        plugin.variants.firstOrNull { v -> v.conditions.all { matches(it, data) } }

    /** Fills {name} / {name:decimals}; null if a value is missing. */
    fun fill(template: String, values: Map<String, Any?>): String? {
        var missing = false
        val text = PluginParser.PLACEHOLDER.replace(template) { match ->
            val value = values[match.groupValues[1]]
            val decimals = match.groupValues[2].toIntOrNull()
            when (value) {
                is Double -> format(value, decimals)
                is Boolean -> if (value) "✓" else "✗"
                is String -> value
                else -> {
                    missing = true
                    ""
                }
            }
        }
        return if (missing) null else text
    }

    /** Locale-independent: whole numbers without decimals unless [decimals] is given, else 1 decimal. */
    fun format(value: Double, decimals: Int?): String {
        val places = decimals ?: if (value == Math.rint(value)) 0 else 1
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).toPlainString()
    }

    /** The plugin's picture, or null if nothing can be shown right now (the toy then skips it). */
    fun render(length: Int, plugin: Plugin, data: PluginData, blinkOn: Boolean): IntArray? {
        val variant = variant(plugin, data) ?: return null
        val frame = when (variant.design) {
            Variant.Design.TEXT -> text(length, variant.text, data.values) ?: return null
            Variant.Design.RING -> {
                val value = data.values[variant.value] as? Double ?: return null
                val percent = percentOf(value, variant)
                val ring = MatrixGraphics.ring(length, percent, MatrixText.BRIGHTNESS_SYMBOL)
                // First text alternative that fits and keeps a gap to the ring; otherwise the ring alone.
                val inside = variant.text.firstNotNullOfOrNull { t ->
                    text(length, listOf(t), data.values)?.takeIf { MatrixGraphics.keepsGapToRing(length, it) }
                }
                if (inside == null) ring else MatrixGraphics.combine(ring, inside)
            }
            Variant.Design.HISTORY -> {
                val series = when (val v = data.values[variant.value]) {
                    is List<*> -> v.filterIsInstance<Double>()
                    is Double -> data.history[variant.value].orEmpty().ifEmpty { listOf(v) }
                    else -> return null
                }
                val bars = MatrixGraphics.historyBars(length, series.map { percentOf(it, variant) }, MatrixText.BRIGHTNESS_SYMBOL)
                val layout = MatrixGraphics.historyLayout(length)
                val label = variant.text.firstNotNullOfOrNull { t ->
                    fill(t, data.values)?.let { MatrixText.renderLineAt(length, it, layout.textY) }
                }
                if (label == null) bars else MatrixGraphics.combine(bars, label)
            }
            Variant.Design.ICON -> icon(length, variant)
        }
        val lit = if (variant.blink && !blinkOn) IntArray(length * length) else frame
        return variant.brightness?.let { MatrixGraphics.scale(lit, it / 255.0) } ?: lit
    }

    /** True if the variant shown right now is marked as a warning. */
    fun isAlert(plugin: Plugin, data: PluginData): Boolean = variant(plugin, data)?.alert == true

    private fun text(length: Int, templates: List<String>, values: Map<String, Any?>): IntArray? {
        val candidates = templates.mapNotNull { fill(it, values)?.split("\n") }
        return MatrixText.render(length, candidates)
    }

    private fun percentOf(value: Double, variant: Variant): Int =
        ((value - variant.min) / (variant.max - variant.min) * 100).roundToInt().coerceIn(0, 100)

    /** '#' full, '+' half, '-' dim. A 13x13 icon is centered on 25x25 when no 25 version is given. */
    private fun icon(length: Int, variant: Variant): IntArray {
        val rows = (if (length == 25) variant.icon25 else null) ?: variant.icon13.orEmpty()
        val size = rows.size
        val offset = (length - size) / 2
        val frame = IntArray(length * length)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val brightness = when (c) {
                    '#' -> 255
                    '+' -> 128
                    '-' -> 40
                    else -> 0
                }
                if (brightness > 0) frame[(y + offset) * length + x + offset] = brightness
            }
        }
        return frame
    }

    /** Adds the newest numeric values to the recorded history. */
    fun record(history: Map<String, List<Double>>, values: Map<String, Any?>): Map<String, List<Double>> =
        values.mapNotNull { (name, value) ->
            (value as? Double)?.let { name to (history[name].orEmpty() + it).takeLast(HISTORY_SAMPLES) }
        }.toMap()
}
