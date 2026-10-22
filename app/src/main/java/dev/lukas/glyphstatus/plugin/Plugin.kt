package dev.lukas.glyphstatus.plugin

import dev.lukas.glyphstatus.Fingerprint
import dev.lukas.glyphstatus.MatrixIcons
import dev.lukas.glyphstatus.MatrixText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A plugin is a JSON document (format described in docs/PLUGINS.md) that declares where data
 * comes from, which values to read from it and how to draw them. Plugins never run code.
 */
data class Plugin(
    val id: String,
    val name: String,
    val source: Source,
    val values: Map<String, ValueSpec>,
    val variants: List<Variant>,
    val secrets: List<SecretSpec>,
    /** The original JSON, stored as-is. */
    val json: String,
)

sealed interface Source {
    val everyMinutes: Int

    data class Http(
        val url: String,
        val headers: Map<String, String>,
        val fingerprint: String,
        val text: Boolean,
        override val everyMinutes: Int,
    ) : Source

    /** GET on the configured Proxmox server, path relative to /api2/json, `{node}` = first online node. */
    data class Proxmox(val path: String, override val everyMinutes: Int) : Source

    /** The values the app already has (see [BuiltinValues]); nothing is fetched. */
    data object Builtin : Source {
        override val everyMinutes = 1
    }
}

data class ValueSpec(
    val kind: Kind,
    /** PATH: path to the value; aggregates: path to the array. */
    val path: String,
    /** Aggregates except COUNT: path inside each array element. */
    val field: String?,
    /** Aggregates: only elements whose fields equal these values. */
    val where: Map<String, Any?>,
    val scale: Double,
) {
    enum class Kind { PATH, COUNT, SUM, MAX, MIN, AVG }
}

data class Condition(val error: Boolean?, val value: String?, val op: String, val than: Any?)

data class Variant(
    val conditions: List<Condition>,
    val design: Design,
    /** Text alternatives, tried in order; "\n" stacks lines. */
    val text: List<String>,
    val value: String?,
    val min: Double,
    val max: Double,
    val icon13: List<String>?,
    val icon25: List<String>?,
    val blink: Boolean,
    /** 1..255; scales the whole picture. */
    val brightness: Int?,
    /** Counts as a warning for the alert modes. */
    val alert: Boolean,
) {
    enum class Design { TEXT, RING, HISTORY, ICON }
}

data class SecretSpec(val key: String, val label: String)

class PluginException(val errors: List<String>) : Exception(errors.joinToString("\n"))

object PluginParser {

    const val FORMAT = 1
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")
    /** {name} or {name:decimals} in text templates. */
    internal val PLACEHOLDER = Regex("\\{([a-z0-9_]+)(?::([0-3]))?}")
    /** {secret:key} in source.url and source.headers. */
    internal val SECRET = Regex("\\{secret:([a-z0-9_]+)}")
    private val OPS = setOf("==", "!=", ">", ">=", "<", "<=")
    private val ICON_CHARS = setOf('.', '#', '+', '-')

    /** Parses and validates; throws [PluginException] listing every problem found. */
    fun parse(json: String): Plugin {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw PluginException(listOf("Kein gültiges JSON: ${e.message}"))
        }
        val errors = mutableListOf<String>()

        if (root.optInt("format", -1) != FORMAT) errors += "\"format\" muss $FORMAT sein"
        val id = root.optString("id")
        if (!ID.matches(id)) errors += "\"id\" muss aus a-z, 0-9 und - bestehen (max. 40 Zeichen)"
        val name = root.optString("name").trim()
        if (name.isEmpty() || name.length > 40) errors += "\"name\" fehlt oder ist länger als 40 Zeichen"

        val secrets = parseSecrets(root.optJSONArray("secrets"), errors)
        val source = parseSource(root.optJSONObject("source"), secrets.map { it.key }.toSet(), errors)
        val values = parseValues(root.optJSONObject("values"), source, errors)
        val known = values.keys + if (source == Source.Builtin) BuiltinValues.NAMES else emptySet()
        val variants = parseVariants(root.optJSONArray("variants"), known, errors)

        if (errors.isNotEmpty()) throw PluginException(errors)
        return Plugin(id, name, source!!, values, variants, secrets, json)
    }

    private fun parseSecrets(array: JSONArray?, errors: MutableList<String>): List<SecretSpec> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i)
            val key = item?.optString("key").orEmpty()
            if (!Regex("[a-z0-9_]{1,40}").matches(key)) {
                errors += "secrets[$i]: \"key\" muss aus a-z, 0-9 und _ bestehen"
                null
            } else {
                SecretSpec(key, item!!.optString("label").ifBlank { key })
            }
        }
    }

    private fun parseSource(obj: JSONObject?, secretKeys: Set<String>, errors: MutableList<String>): Source? {
        if (obj == null) {
            errors += "\"source\" fehlt"
            return null
        }
        val every = obj.optInt("every_minutes", 1)
        if (every !in 1..1440) errors += "source.every_minutes muss 1..1440 sein"
        return when (obj.optString("type")) {
            "http" -> {
                val url = obj.optString("url")
                if (!url.startsWith("https://")) errors += "source.url muss mit https:// beginnen"
                val headers = obj.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }
                    .orEmpty()
                for (text in listOf(url) + headers.values) {
                    SECRET.findAll(text).map { it.groupValues[1] }.filter { it !in secretKeys }.forEach {
                        errors += "{secret:$it} ist nicht unter \"secrets\" deklariert"
                    }
                }
                val fingerprint = obj.optString("fingerprint")
                if (fingerprint.isNotEmpty() && Fingerprint.parse(fingerprint) == null) {
                    errors += "source.fingerprint muss 64 Hex-Zeichen (SHA-256) haben"
                }
                val format = obj.optString("format", "json")
                if (format != "json" && format != "text") errors += "source.format muss \"json\" oder \"text\" sein"
                Source.Http(url, headers, fingerprint, format == "text", every)
            }
            "proxmox" -> {
                val path = obj.optString("path").trimStart('/')
                if (path.isEmpty()) errors += "source.path fehlt"
                Source.Proxmox(path, every)
            }
            "builtin" -> Source.Builtin
            else -> {
                errors += "source.type muss \"http\", \"proxmox\" oder \"builtin\" sein"
                null
            }
        }
    }

    private fun parseValues(obj: JSONObject?, source: Source?, errors: MutableList<String>): Map<String, ValueSpec> {
        // Optional: a plugin that only reacts to {"error": true} needs no values.
        if (obj == null) return emptyMap()
        if (source == Source.Builtin) errors += "Bei source.type \"builtin\" gibt es keine eigenen \"values\""
        val result = mutableMapOf<String, ValueSpec>()
        for (name in obj.keys()) {
            if (!Regex("[a-z0-9_]{1,30}").matches(name)) {
                errors += "values: Name \"$name\" muss aus a-z, 0-9 und _ bestehen"
                continue
            }
            val spec = obj.optJSONObject(name)
            if (spec == null) {
                errors += "values.$name muss ein Objekt sein"
                continue
            }
            val kinds = ValueSpec.Kind.entries.filter { spec.has(it.name.lowercase()) }
            if (kinds.size != 1) {
                errors += "values.$name braucht genau eines von: path, count, sum, max, min, avg"
                continue
            }
            val kind = kinds.single()
            val field = spec.optString("field").takeIf { it.isNotEmpty() }
            if (kind !in listOf(ValueSpec.Kind.PATH, ValueSpec.Kind.COUNT) && field == null) {
                errors += "values.$name: \"${kind.name.lowercase()}\" braucht \"field\""
            }
            val where = spec.optJSONObject("where")?.let { w -> w.keys().asSequence().associateWith { normalize(w.opt(it)) } }
                .orEmpty()
            result[name] = ValueSpec(kind, spec.optString(kind.name.lowercase()), field, where, spec.optDouble("scale", 1.0))
        }
        return result
    }

    private fun parseVariants(array: JSONArray?, known: Set<String>, errors: MutableList<String>): List<Variant> {
        if (array == null || array.length() == 0) {
            errors += "\"variants\" fehlt oder ist leer"
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i)
            if (obj == null) {
                errors += "variants[$i] muss ein Objekt sein"
                return@mapNotNull null
            }
            val where = "variants[$i]"
            val design = Variant.Design.entries.find { it.name.lowercase() == obj.optString("design") }
            if (design == null) errors += "$where.design muss text, ring, history oder icon sein"

            val conditions = parseConditions(obj.opt("if"), known, where, errors)
            val text = when (val t = obj.opt("text")) {
                null -> emptyList()
                is String -> listOf(t)
                is JSONArray -> (0 until t.length()).map { t.optString(it) }
                else -> emptyList<String>().also { errors += "$where.text muss Text oder Liste sein" }
            }
            text.forEach { checkTemplate(it, known, "$where.text", errors) }
            if (design == Variant.Design.TEXT && text.isEmpty()) errors += "$where: design text braucht \"text\""

            val value = obj.optString("value").takeIf { it.isNotEmpty() }
            if (design == Variant.Design.RING || design == Variant.Design.HISTORY) {
                if (value == null) errors += "$where: design ${design.name.lowercase()} braucht \"value\""
                else if (value !in known) errors += "$where.value \"$value\" ist kein bekannter Wert"
            }
            val min = obj.optDouble("min", 0.0)
            val max = obj.optDouble("max", 100.0)
            if (max <= min) errors += "$where: max muss größer als min sein"

            val icon13 = obj.optJSONObject("icon")?.optJSONArray("13")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val icon25 = obj.optJSONObject("icon")?.optJSONArray("25")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            if (design == Variant.Design.ICON) {
                if (icon13 == null) errors += "$where: design icon braucht icon.13"
                icon13?.let { checkIcon(it, 13, "$where.icon.13", errors) }
                icon25?.let { checkIcon(it, 25, "$where.icon.25", errors) }
            }
            val brightness = if (obj.has("brightness")) obj.optInt("brightness") else null
            if (brightness != null && brightness !in 1..255) errors += "$where.brightness muss 1..255 sein"

            design?.let {
                Variant(
                    conditions, it, text, value, min, max, icon13, icon25,
                    blink = obj.optBoolean("blink", false),
                    brightness = brightness,
                    alert = obj.optBoolean("alert", false),
                )
            }
        }
    }

    private fun parseConditions(raw: Any?, known: Set<String>, where: String, errors: MutableList<String>): List<Condition> {
        val list = when (raw) {
            null -> return emptyList()
            is JSONObject -> listOf(raw)
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            else -> return emptyList<Condition>().also { errors += "$where.if muss Objekt oder Liste sein" }
        }
        return list.map { c ->
            if (c.has("error")) {
                Condition(error = c.optBoolean("error"), value = null, op = "==", than = null)
            } else {
                val value = c.optString("value")
                if (value !in known) errors += "$where.if: \"$value\" ist kein bekannter Wert"
                val op = c.optString("op", "==")
                if (op !in OPS) errors += "$where.if: op muss einer von ${OPS.joinToString(" ")} sein"
                Condition(error = null, value = value, op = op, than = normalize(c.opt("than")))
            }
        }
    }

    private fun checkTemplate(template: String, known: Set<String>, where: String, errors: MutableList<String>) {
        for (match in PLACEHOLDER.findAll(template)) {
            if (match.groupValues[1] !in known) errors += "$where: {${match.groupValues[1]}} ist kein bekannter Wert"
        }
        val literal = PLACEHOLDER.replace(template, "")
        if ('{' in literal || '}' in literal) errors += "$where: ungültiger Platzhalter in \"$template\""
        for (char in literal.replace("\n", "")) {
            if (MatrixText.glyph(13, char) == null) errors += "$where: Zeichen '$char' gibt es in der Pixel-Schrift nicht"
        }
    }

    private fun checkIcon(rows: List<String>, length: Int, where: String, errors: MutableList<String>) {
        if (rows.size != length || rows.any { it.length != length }) {
            errors += "$where muss $length Zeilen mit je $length Zeichen haben"
            return
        }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c !in ICON_CHARS) errors += "$where: Zeichen '$c' (erlaubt: . # + -)"
                else if (c != '.' && !MatrixIcons.isVisible(length, x, y)) {
                    errors += "$where: Pixel ($x,$y) liegt außerhalb des runden Displays"
                }
            }
        }
    }

    /** JSON numbers become Double, numeric strings too; JSON null becomes null. */
    fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: value
        else -> value
    }
}
