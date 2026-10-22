package dev.lukas.glyphstatus.plugin

import dev.lukas.glyphstatus.CachedStatus
import dev.lukas.glyphstatus.DisplaySettings
import dev.lukas.glyphstatus.MatrixIcons
import dev.lukas.glyphstatus.MatrixText
import dev.lukas.glyphstatus.Screens
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PluginTest {

    private fun plugin(variants: String, values: String = """{"v": {"path": "v"}}""", extra: String = "") = PluginParser.parse(
        """{"format": 1, "id": "t", "name": "Test", $extra
            "source": {"type": "http", "url": "https://x.example"},
            "values": $values, "variants": $variants}""",
    )

    private fun errorsOf(json: String): List<String> = try {
        PluginParser.parse(json)
        emptyList()
    } catch (e: PluginException) {
        e.errors
    }

    private fun lit(frame: IntArray?) = frame?.count { it > 0 } ?: 0

    private fun assertInside(length: Int, frame: IntArray, what: String) {
        frame.forEachIndexed { i, v ->
            if (v > 0) assertTrue("$what (${i % length},${i / length})", MatrixIcons.isVisible(length, i % length, i / length))
        }
    }

    // --- examples shipped in the repository -------------------------------------------------

    private fun exampleFiles(): List<File> {
        val dir = File(System.getProperty("plugins.dir") ?: "../plugins/examples")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue("no example plugins found in ${dir.absolutePath}", files.isNotEmpty())
        return files
    }

    @Test
    fun everyExampleParsesAndDraws() {
        val status = CachedStatus(true, 90_000, 7, 9, 85, 40, 47, 0.5, 12, true, 0)
        for (file in exampleFiles()) {
            val plugin = try {
                PluginParser.parse(file.readText())
            } catch (e: PluginException) {
                fail("${file.name}:\n${e.message}")
                return
            }
            // sample values: every declared value as 7, the built-in ones from a status
            val values = plugin.values.keys.associateWith { 7.0 as Any? } + BuiltinValues.of(status, 0)
            for (length in listOf(13, 25)) {
                for (error in listOf(false, true)) {
                    val data = PluginData(if (error) emptyMap() else values, error)
                    val frame = PluginEngine.render(length, plugin, data, blinkOn = true)
                    frame?.let { assertInside(length, it, "${file.name} on $length, error=$error") }
                }
                val normal = PluginEngine.render(length, plugin, PluginData(values, false), true)
                assertTrue("${file.name} draws nothing on $length", lit(normal) > 0)
            }
        }
    }

    @Test
    fun everyCompletePluginInTheDocsIsValid() {
        val docs = File(System.getProperty("plugins.docs") ?: "../docs/PLUGINS.md").readText()
        val blocks = Regex("```json\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(docs)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("{") && "\"format\"" in it }
            .toList()
        assertTrue("no complete plugin examples found in PLUGINS.md", blocks.isNotEmpty())
        blocks.forEach { block ->
            try {
                PluginParser.parse(block)
            } catch (e: PluginException) {
                fail("PLUGINS.md example invalid:\n${e.message}\n$block")
            }
        }
    }

    @Test
    fun builtinPluginsFetchWhatTheyUse() {
        val p = PluginParser.parse(
            """{"format": 1, "id": "b", "name": "B", "source": {"type": "builtin"}, "variants": [
                {"if": {"value": "backup_ok", "op": "==", "than": false}, "design": "text", "text": "B!"},
                {"design": "history", "value": "cpu_history", "text": "D{disk}"}]}""",
        )
        assertEquals(setOf(Screens.Fetch.TASKS, Screens.Fetch.RRD, Screens.Fetch.RESOURCES), BuiltinValues.fetchesFor(p))
        val http = plugin("""[{"design": "text", "text": "{v}"}]""")
        assertEquals(emptySet<Screens.Fetch>(), BuiltinValues.fetchesFor(http))
    }

    // --- validation ---------------------------------------------------------------------------

    @Test
    fun invalidPluginsAreRejectedWithReadableErrors() {
        assertTrue(errorsOf("not json").single().startsWith("Kein gültiges JSON"))
        val base = """"format": 1, "id": "ok", "name": "N", "source": {"type": "http", "url": "https://x"}, "values": {"v": {"path": "v"}}"""
        assertTrue(errorsOf("""{"format": 2, "id": "Bad Id", "name": "", "variants": []}""").size >= 4)
        assertTrue(errorsOf("""{$base, "variants": [{"design": "text", "text": "{nope}"}]}""").any { "nope" in it })
        assertTrue(errorsOf("""{$base, "variants": [{"design": "text", "text": "€"}]}""").any { "Pixel-Schrift" in it })
        assertTrue(errorsOf("""{$base, "variants": [{"design": "ring"}]}""").any { "value" in it })
        val outside = (listOf("#" + ".".repeat(12)) + List(12) { ".".repeat(13) }).joinToString(",") { "\"$it\"" }
        assertTrue(errorsOf("""{$base, "variants": [{"design": "icon", "icon": {"13": [$outside]}}]}""").any { "außerhalb" in it })
        assertTrue(errorsOf("""{$base, "variants": [{"design": "icon", "icon": {"13": ["#"]}}]}""").any { "13 Zeilen" in it })
        val http = """"format": 1, "id": "ok", "name": "N", "variants": [{"design": "text", "text": "A"}]"""
        assertTrue(errorsOf("""{$http, "source": {"type": "http", "url": "http://x"}}""").any { "https" in it })
        assertTrue(errorsOf("""{$http, "source": {"type": "http", "url": "https://x/{secret:k}"}}""").any { "secret:k" in it })
        assertTrue(errorsOf("""{$http, "source": {"type": "builtin"}, "values": {"v": {"path": "v"}}}""").any { "builtin" in it })
        assertEquals(emptyList<String>(), errorsOf("""{$base, "variants": [{"design": "text", "text": "v{v:1}"}]}"""))
    }

    // --- values ---------------------------------------------------------------------------------

    @Test
    fun valuesAreExtractedFromJson() {
        val body = JSONObject(
            """{"state": "21.46", "on": true, "data": [
                {"type": "lxc", "status": "running", "mem": 100},
                {"type": "lxc", "status": "stopped", "mem": 50},
                {"type": "qemu", "status": "running", "mem": 300, "tags": [{"n": 9}]}]}""",
        )
        fun spec(json: String) = plugin("""[{"design": "text", "text": "A"}]""", """{"x": $json}""").values.getValue("x")
        assertEquals(21.46, PluginEngine.extract(spec("""{"path": "state"}"""), body))
        assertEquals(true, PluginEngine.extract(spec("""{"path": "on"}"""), body))
        assertEquals(9.0, PluginEngine.extract(spec("""{"path": "data[2].tags[0].n"}"""), body))
        assertNull(PluginEngine.extract(spec("""{"path": "data[7].mem"}"""), body))
        assertEquals(2.0, PluginEngine.extract(spec("""{"count": "data", "where": {"status": "running"}}"""), body))
        assertEquals(1.0, PluginEngine.extract(spec("""{"count": "data", "where": {"type": "lxc", "status": "running"}}"""), body))
        assertEquals(450.0, PluginEngine.extract(spec("""{"sum": "data", "field": "mem"}"""), body))
        assertEquals(300.0, PluginEngine.extract(spec("""{"max": "data", "field": "mem"}"""), body))
        assertEquals(150.0, PluginEngine.extract(spec("""{"avg": "data", "field": "mem"}"""), body))
        assertEquals(0.3, PluginEngine.extract(spec("""{"max": "data", "field": "mem", "scale": 0.001}"""), body))
    }

    @Test
    fun templatesAreFilledLocaleIndependently() {
        val values = mapOf("t" to 21.46, "n" to 7.0, "ok" to true, "s" to "on", "half" to 0.15)
        assertEquals("T21", PluginEngine.fill("T{t:0}", values))
        assertEquals("21.5", PluginEngine.fill("{t}", values))
        assertEquals("7", PluginEngine.fill("{n}", values))
        assertEquals("0.2", PluginEngine.fill("{half:1}", values))
        assertEquals("B✓", PluginEngine.fill("B{ok}", values))
        assertEquals("on", PluginEngine.fill("{s}", values))
        assertNull(PluginEngine.fill("{missing}", values))
    }

    // --- variants and drawing -------------------------------------------------------------------

    @Test
    fun firstMatchingVariantWins() {
        val p = plugin(
            """[{"if": {"error": true}, "design": "text", "text": "E"},
                {"if": [{"value": "v", "op": ">=", "than": 10}, {"value": "v", "op": "<", "than": 20}], "design": "text", "text": "MID", "alert": true},
                {"if": {"value": "v", "op": "==", "than": "off"}, "design": "text", "text": "OFF"},
                {"design": "text", "text": "{v}"}]""",
        )
        fun shown(data: PluginData) = PluginEngine.render(13, p, data, true)
        assertArrayEquals(MatrixText.render(13, listOf(listOf("E"))), shown(PluginData(emptyMap(), true)))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("MID"))), shown(PluginData(mapOf("v" to 12.0), false)))
        assertTrue(PluginEngine.isAlert(p, PluginData(mapOf("v" to 12.0), false)))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("OFF"))), shown(PluginData(mapOf("v" to "off"), false)))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("25"))), shown(PluginData(mapOf("v" to 25.0), false)))
        assertFalse(PluginEngine.isAlert(p, PluginData(mapOf("v" to 25.0), false)))
        // nothing to show when the last variant's value is missing
        assertNull(shown(PluginData(emptyMap(), false)))
    }

    @Test
    fun designsDrawInsideTheCircle() {
        val p = plugin(
            """[{"if": {"value": "v", "op": "<", "than": 0}, "design": "icon", "icon": {"13": [${List(13) { "\"" + (if (it == 6) "......#......" else ".............") + "\"" }.joinToString(",")}]}},
                {"if": {"value": "v", "op": "<", "than": 50}, "design": "ring", "value": "v", "text": ["V{v}", "{v}"]},
                {"if": {"value": "v", "op": "<", "than": 80}, "design": "history", "value": "v", "text": "V{v}"},
                {"design": "text", "text": ["VERY LONG TEXT", "{v}"], "blink": true, "brightness": 128}]""",
        )
        for (length in listOf(13, 25)) {
            for (v in listOf(-1.0, 0.0, 30.0, 49.0, 60.0, 99.0, 100.0)) {
                val data = PluginData(mapOf("v" to v), false, mapOf("v" to listOf(10.0, 90.0, v)))
                val frame = PluginEngine.render(length, p, data, true)
                assertNotNull("$v on $length", frame)
                assertInside(length, frame!!, "$v on $length")
                assertTrue("$v on $length", lit(frame) > 0)
            }
            // ring: "V30" touches the ring on 13x13, so the shorter "30" is used there
            val ring = PluginEngine.render(length, p, PluginData(mapOf("v" to 30.0), false), true)!!
            val expectedText = if (length == 13) "30" else "V30"
            val textOnly = MatrixText.render(length, listOf(listOf(expectedText)))!!
            textOnly.forEachIndexed { i, v -> if (v > 0) assertEquals("$length text pixel $i", v, ring[i]) }
            // icon centered on 25x25 when only a 13x13 icon is given
            val icon = PluginEngine.render(length, p, PluginData(mapOf("v" to -1.0), false), true)!!
            assertEquals(255, icon[(length / 2) * length + length / 2])
            // blink + brightness
            val on = PluginEngine.render(length, p, PluginData(mapOf("v" to 99.0), false), true)!!
            assertEquals(128, on.max())
            assertEquals(0, lit(PluginEngine.render(length, p, PluginData(mapOf("v" to 99.0), false), false)))
        }
    }

    @Test
    fun historyIsRecordedAndCapped() {
        var history = emptyMap<String, List<Double>>()
        repeat(30) { history = PluginEngine.record(history, mapOf("v" to it.toDouble(), "s" to "text")) }
        assertEquals(PluginEngine.HISTORY_SAMPLES, history.getValue("v").size)
        assertEquals(29.0, history.getValue("v").last(), 0.0)
        assertFalse(history.containsKey("s"))
    }

    @Test
    fun builtinValuesMirrorTheStatus() {
        val status = CachedStatus(true, 2 * 86_400L, 7, 9, 35, 62, 47, 0.5, 12, false, 1_000, cpuHistory = listOf(1, 2))
        val values = BuiltinValues.of(status, 1_000 + 3_600_000)
        assertEquals(BuiltinValues.NAMES, values.keys)
        assertEquals(2.0, values["uptime_days"])
        assertEquals(49.0, values["uptime_hours"])
        assertEquals(2.0, values["guests_stopped"])
        assertEquals(false, values["backup_ok"])
        assertEquals(listOf(1.0, 2.0), values["cpu_history"])
        assertNull(values["mem_history"])
    }

    // --- rotation -------------------------------------------------------------------------------

    @Test
    fun pluginsJoinTheRotationBeforeTheClock() {
        val settings = DisplaySettings.DEFAULT
        val status = CachedStatus(true, 90_000, 7, 9, 35, 62, 47, 0.5, 12, true, 0)
        val shows = Screens.PluginDisplay("a", alert = false) { length, _ -> MatrixText.render(length, listOf(listOf("A"))) }
        val empty = Screens.PluginDisplay("b", alert = true) { _, _ -> null }
        val warns = Screens.PluginDisplay("c", alert = true) { length, _ -> MatrixText.render(length, listOf(listOf("C"))) }

        val slots = Screens.slots(status, settings, listOf(shows, empty, warns), 13)
        assertEquals(
            listOf("UPTIME", "GUESTS", "a", "c", "CLOCK"),
            slots.map { if (it is Screens.Slot.Builtin) it.view.name else (it as Screens.Slot.Extra).plugin.id },
        )
        val only = Screens.slots(status, settings.copy(alertMode = Screens.AlertMode.ONLY), listOf(shows, warns), 13)
        assertEquals(listOf("c"), only.map { (it as Screens.Slot.Extra).plugin.id })

        // the plugin's picture is what the toy shows in its slot
        val frame = Screens.compose(13, status, settings.copy(intervalSeconds = 3), 6 * 2, 0, 12, 0, true, listOf(shows))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("A"))), frame)
    }
}
