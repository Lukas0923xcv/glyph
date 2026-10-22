package dev.lukas.glyphstatus

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

data class CheckResult(
    val up: Boolean,
    val message: String,
    /** Uptime of the most recently booted online node, in seconds. */
    val uptimeSeconds: Long? = null,
    /** Running / total VMs and LXC containers (templates excluded). */
    val guestsRunning: Int? = null,
    val guestsTotal: Int? = null,
    /** Highest CPU / RAM usage of the online nodes, 0..100. */
    val cpuPercent: Int? = null,
    val memPercent: Int? = null,
    /** Used space of all storages holding VM disks or containers, combined, 0..100. */
    val diskPercent: Int? = null,
    /** Highest 1-minute load average of the online nodes. */
    val loadAverage: Double? = null,
    /** Network traffic (in + out) of all online nodes, Mbit/s, last minute's average. */
    val netMbit: Int? = null,
    /** Result of the most recent finished backup (vzdump) task; null if none is listed. */
    val backupOk: Boolean? = null,
    /** One char per VM/LXC ordered by VMID: 'r' running, 's' stopped, 'l' locked. */
    val guestStates: String? = null,
    /** CPU / RAM usage per minute, oldest first (highest node per minute). */
    val cpuHistory: List<Int> = emptyList(),
    val memHistory: List<Int> = emptyList(),
    /** Number of pending package updates. */
    val updates: Int? = null,
    /** False if a disk reports a failed SMART check or a ZFS pool is not ONLINE. */
    val healthOk: Boolean? = null,
    /** Cluster quorum; null on a standalone node. */
    val quorate: Boolean? = null,
    /** The server could not be reached at all (no HTTP answer), as opposed to answering with an error. */
    val connectionFailed: Boolean = false,
    /** Names of the online nodes (for plugin paths containing {node}). */
    val nodes: List<String> = emptyList(),
)

object ProxmoxClient {

    private const val TIMEOUT_SECONDS = 5L
    private const val HISTORY_MINUTES = 24

    /**
     * Blocking. Must not be called on the main thread.
     * UP only if the request succeeds and every node reports "status": "online".
     * When UP, the extra data in [fetches] is requested as well; a failing extra request
     * only leaves that value empty and does not affect UP.
     */
    fun check(config: ServerConfig, fetches: Set<Screens.Fetch> = Screens.Fetch.entries.toSet()): CheckResult {
        val base = config.host.trim().trimEnd('/').toHttpUrlOrNull()
            ?: return CheckResult(false, "Ungültige Host-URL")
        if (config.tokenId.isBlank() || config.secret.isBlank()) {
            return CheckResult(false, "Token-ID oder Secret fehlt")
        }

        val client = try {
            buildClient(config.fingerprint)
        } catch (e: IllegalArgumentException) {
            return CheckResult(false, e.message ?: "Ungültiger Fingerprint")
        }

        val auth = "PVEAPIToken=${config.tokenId.trim()}=${config.secret.trim()}"
        fun request(path: String, vararg query: Pair<String, String>) = Request.Builder()
            .url(
                base.newBuilder().addPathSegments(path)
                    .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
                    .build()
            )
            .header("Authorization", auth)
            .get()
            .build()

        /** GET returning the HTTP code (null on connection errors) and the "data" field. */
        fun fetchWithCode(path: String, vararg query: Pair<String, String>): Pair<Int?, Any?> = try {
            client.newCall(request(path, *query)).execute().use { response ->
                val data = if (response.isSuccessful) JSONObject(response.body?.string().orEmpty()).opt("data") else null
                response.code to data
            }
        } catch (e: Exception) {
            null to null
        }

        fun fetch(path: String, vararg query: Pair<String, String>): Any? = fetchWithCode(path, *query).second

        val nodesRequest = try {
            request("api2/json/nodes")
        } catch (e: IllegalArgumentException) {
            return CheckResult(false, "Ungültige Zeichen in Token-ID oder Secret")
        }

        try {
            val nodes = try {
                client.newCall(nodesRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return CheckResult(false, "HTTP ${response.code} ${response.message}".trim())
                    }
                    evaluateNodes(response.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                return CheckResult(
                    false,
                    "${e.javaClass.simpleName}: ${e.message ?: "Verbindungsfehler"}",
                    connectionFailed = true,
                )
            }
            var result = nodes.result.copy(nodes = nodes.online)
            if (!result.up) return result
            val details = StringBuilder(result.message)

            if (Screens.Fetch.RESOURCES in fetches) {
                val resources = (fetch("api2/json/cluster/resources") as? JSONArray)?.let(::parseResources)
                if (resources == null) {
                    details.append(" · VMs/LXC/Storage nicht lesbar")
                } else {
                    details.append(" · ${resources.guestsRunning}/${resources.guestsTotal} VMs/LXC laufen")
                    val locked = resources.guestStates.count { it == 'l' }
                    if (locked > 0) details.append(" ($locked gesperrt)")
                    resources.diskPercent?.let { details.append(" · Disk $it %") }
                    result = result.copy(
                        guestsRunning = resources.guestsRunning,
                        guestsTotal = resources.guestsTotal,
                        guestStates = resources.guestStates,
                        diskPercent = resources.diskPercent,
                    )
                }
            }

            if (Screens.Fetch.LOAD in fetches) {
                val loads = nodes.online.mapNotNull { node ->
                    ((fetch("api2/json/nodes/$node/status") as? JSONObject)?.optJSONArray("loadavg"))
                        ?.optString(0)?.toDoubleOrNull()
                }
                loads.maxOrNull()?.let {
                    details.append(" · Load $it")
                    result = result.copy(loadAverage = it)
                }
            }

            if (Screens.Fetch.RRD in fetches) {
                val perNode = nodes.online.map { node ->
                    (fetch("api2/json/nodes/$node/rrddata", "timeframe" to "hour", "cf" to "AVERAGE") as? JSONArray)
                        ?.let(::parseRrd)
                }
                if (perNode.isNotEmpty() && perNode.all { it != null }) {
                    val rrd = perNode.filterNotNull()
                    val traffic = rrd.map { it.trafficBytesPerSecond }
                    if (traffic.all { it != null }) {
                        val mbit = (traffic.sumOf { it!! } * 8 / 1_000_000).roundToInt()
                        details.append(" · Netz $mbit Mbit/s")
                        result = result.copy(netMbit = mbit)
                    }
                    result = result.copy(
                        cpuHistory = maxPerMinute(rrd.map { it.cpu }),
                        memHistory = maxPerMinute(rrd.map { it.mem }),
                    )
                }
            }

            if (Screens.Fetch.TASKS in fetches) {
                (fetch("api2/json/cluster/tasks") as? JSONArray)?.let(::lastBackupOk)?.let { ok ->
                    details.append(if (ok) " · letztes Backup OK" else " · letztes Backup FEHLGESCHLAGEN")
                    result = result.copy(backupOk = ok)
                }
            }

            if (Screens.Fetch.UPDATES in fetches) {
                val answers = nodes.online.map { node -> fetchWithCode("api2/json/nodes/$node/apt/update") }
                when {
                    answers.any { it.first == 403 } ->
                        details.append(" · Updates: keine Berechtigung (Proxmox verlangt Sys.Modify)")
                    answers.all { it.second is JSONArray } -> {
                        val count = answers.sumOf { (it.second as JSONArray).length() }
                        details.append(" · $count Update(s)")
                        result = result.copy(updates = count)
                    }
                    else -> details.append(" · Updates nicht lesbar")
                }
            }

            if (Screens.Fetch.HEALTH in fetches) {
                val healthy = nodes.online.map { node ->
                    nodeHealth(fetch("api2/json/nodes/$node/disks/list"), fetch("api2/json/nodes/$node/disks/zfs"))
                }
                if (healthy.isNotEmpty() && healthy.all { it != null }) {
                    val ok = healthy.all { it == true }
                    details.append(if (ok) " · Disks OK" else " · DISK-FEHLER (SMART/ZFS)")
                    result = result.copy(healthOk = ok)
                } else {
                    details.append(" · Disk-Health nicht lesbar")
                }
            }

            if (Screens.Fetch.QUORUM in fetches) {
                (fetch("api2/json/cluster/status") as? JSONArray)?.let(::parseQuorum)?.let { quorate ->
                    details.append(if (quorate) " · Quorum OK" else " · KEIN QUORUM")
                    result = result.copy(quorate = quorate)
                }
            }

            return result.copy(message = details.toString())
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private class Resources(val guestsRunning: Int, val guestsTotal: Int, val guestStates: String, val diskPercent: Int?)

    /**
     * Counts VMs and LXC containers (ignoring templates) and combines the used space of all
     * available storages that hold VM disks or container volumes (all available storages if none
     * declares that content). Shared storages are listed once per node, so they are counted once.
     */
    private fun parseResources(items: JSONArray): Resources {
        val guests = sortedMapOf<Int, Char>()
        // A locked guest (backup, migration, …) may still be running; it counts as running in "x/y".
        var running = 0
        val guestStorages = mutableMapOf<String, Pair<Long, Long>>()
        val allStorages = mutableMapOf<String, Pair<Long, Long>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "qemu", "lxc" -> {
                    if (item.optInt("template", 0) == 1) continue
                    if (item.optString("status") == "running") running++
                    guests[item.optInt("vmid")] = when {
                        item.optString("lock").isNotEmpty() -> 'l'
                        item.optString("status") == "running" -> 'r'
                        else -> 's'
                    }
                }
                "storage" -> {
                    val max = item.optLong("maxdisk", 0)
                    if (item.optString("status") != "available" || max <= 0) continue
                    val name = item.optString("storage")
                    val key = if (item.optInt("shared", 0) == 1) name else "${item.optString("node")}/$name"
                    val usage = item.optLong("disk", 0) to max
                    allStorages[key] = usage
                    val content = item.optString("content").split(',')
                    if ("images" in content || "rootdir" in content) guestStorages[key] = usage
                }
            }
        }
        val counted = guestStorages.ifEmpty { allStorages }
        val used = counted.values.sumOf { it.first }
        val size = counted.values.sumOf { it.second }
        val disk = if (size > 0) percent(used.toDouble() / size) else null
        return Resources(running, guests.size, guests.values.joinToString(""), disk)
    }

    private class Rrd(val cpu: List<Int>, val mem: List<Int>, val trafficBytesPerSecond: Double?)

    /** Per-minute CPU/RAM usage (oldest first) and the newest network traffic from a node's rrd data. */
    private fun parseRrd(samples: JSONArray): Rrd {
        val cpu = mutableListOf<Int>()
        val mem = mutableListOf<Int>()
        var traffic: Double? = null
        for (i in 0 until samples.length()) {
            val sample = samples.optJSONObject(i) ?: continue
            if (sample.has("cpu")) cpu += percent(sample.optDouble("cpu", 0.0))
            val total = sample.optDouble("memtotal", 0.0)
            if (sample.has("memused") && total > 0) mem += percent(sample.optDouble("memused", 0.0) / total)
            if (sample.has("netin") && sample.has("netout")) {
                traffic = sample.optDouble("netin", 0.0) + sample.optDouble("netout", 0.0)
            }
        }
        return Rrd(cpu.takeLast(HISTORY_MINUTES), mem.takeLast(HISTORY_MINUTES), traffic)
    }

    /** Aligns the series at their newest value and takes the highest node per minute. */
    private fun maxPerMinute(series: List<List<Int>>): List<Int> {
        val length = series.maxOfOrNull { it.size } ?: return emptyList()
        return (0 until length).map { fromEnd ->
            series.mapNotNull { it.getOrNull(it.size - length + fromEnd) }.maxOrNull() ?: 0
        }
    }

    /** Status of the most recently finished vzdump task; "OK" and "WARNINGS: …" count as success. */
    private fun lastBackupOk(tasks: JSONArray): Boolean? {
        var latestEnd = -1L
        var latestStatus: String? = null
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            if (task.optString("type") != "vzdump" || !task.has("endtime")) continue
            val end = task.optLong("endtime")
            if (end > latestEnd) {
                latestEnd = end
                latestStatus = task.optString("status")
            }
        }
        val status = latestStatus ?: return null
        return status == "OK" || status.startsWith("WARNINGS")
    }

    /**
     * False if a disk's SMART health is FAILED or a ZFS pool is not ONLINE; null if neither list
     * could be read. Disks without SMART support report UNKNOWN and are ignored.
     */
    private fun nodeHealth(disks: Any?, pools: Any?): Boolean? {
        if (disks !is JSONArray && pools !is JSONArray) return null
        if (disks is JSONArray) {
            for (i in 0 until disks.length()) {
                val health = disks.optJSONObject(i)?.optString("health").orEmpty()
                if (health.contains("FAIL", ignoreCase = true)) return false
            }
        }
        if (pools is JSONArray) {
            for (i in 0 until pools.length()) {
                val health = pools.optJSONObject(i)?.optString("health").orEmpty()
                if (health.isNotEmpty() && health != "ONLINE") return false
            }
        }
        return true
    }

    /** "quorate" of the cluster entry; null on a standalone node (no cluster entry). */
    private fun parseQuorum(entries: JSONArray): Boolean? {
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("type") == "cluster" && entry.has("quorate")) return entry.optInt("quorate") == 1
        }
        return null
    }

    private fun percent(fraction: Double): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

    private class Nodes(val result: CheckResult, val online: List<String>)

    private fun evaluateNodes(body: String): Nodes {
        val nodes = try {
            JSONObject(body).getJSONArray("data")
        } catch (e: Exception) {
            return Nodes(CheckResult(false, "Unerwartete Antwort: ${e.message}"), emptyList())
        }
        if (nodes.length() == 0) return Nodes(CheckResult(false, "Keine Nodes in der Antwort"), emptyList())

        val online = mutableListOf<String>()
        val offline = mutableListOf<String>()
        var uptime: Long? = null
        var cpu: Int? = null
        var mem: Int? = null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optString("status") != "online") {
                offline += "${node.optString("node", "?")} (${node.optString("status", "unbekannt")})"
                continue
            }
            online += node.optString("node")
            // uptime/cpu/mem are only present when the token has Sys.Audit on the node.
            if (node.has("uptime")) {
                val seconds = node.optLong("uptime")
                uptime = uptime?.let { minOf(it, seconds) } ?: seconds
            }
            if (node.has("cpu")) cpu = maxOf(cpu ?: 0, percent(node.optDouble("cpu", 0.0)))
            val maxMem = node.optLong("maxmem", 0)
            if (node.has("mem") && maxMem > 0) mem = maxOf(mem ?: 0, percent(node.optLong("mem").toDouble() / maxMem))
        }
        if (offline.isNotEmpty()) return Nodes(CheckResult(false, "Nicht online: ${offline.joinToString()}"), online)

        val details = buildString {
            uptime?.let { append(" · Uptime ${it / 86_400} T ${it % 86_400 / 3_600} h") }
            cpu?.let { append(" · CPU $it %") }
            mem?.let { append(" · RAM $it %") }
            if (uptime == null) append(" · keine Uptime/CPU/RAM – Token braucht PVEAuditor (Sys.Audit)")
        }
        val result = CheckResult(
            true,
            "${nodes.length()} Node(s) online$details",
            uptimeSeconds = uptime,
            cpuPercent = cpu,
            memPercent = mem,
        )
        return Nodes(result, online)
    }

    /**
     * Authenticated GET on a Proxmox API path relative to /api2/json (for plugins). Blocking.
     * Returns the response body; throws IOException with a readable message on any failure.
     */
    fun get(config: ServerConfig, path: String): String {
        val base = config.host.trim().trimEnd('/').toHttpUrlOrNull() ?: throw IOException("Ungültige Host-URL")
        val url = base.newBuilder().addPathSegments("api2/json").build().toString().trimEnd('/') + "/" + path.trimStart('/')
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "PVEAPIToken=${config.tokenId.trim()}=${config.secret.trim()}")
            .get()
            .build()
        val client = buildClient(config.fingerprint)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                return response.body?.string().orEmpty()
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /** OkHttp client with the 5 s timeouts; pins the certificate when [fingerprint] is set. */
    fun buildClient(fingerprint: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Without a fingerprint OkHttp keeps the system CAs and the default hostname verifier.
        if (fingerprint.isNotBlank()) {
            val pin = Fingerprint.parse(fingerprint)
                ?: throw IllegalArgumentException("Fingerprint muss 64 Hex-Zeichen (SHA-256) haben")
            val trustManager = PinnedTrustManager(pin)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // The certificate itself is pinned, so the server's identity is fixed by the pin
            // (Proxmox' self-signed certificate often lacks the IP the user connects to).
            builder.hostnameVerifier { _, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                leaf != null && trustManager.matches(leaf)
            }
        }
        return builder.build()
    }
}

/** Accepts exactly one server certificate: the one whose DER SHA-256 equals [pin]. */
class PinnedTrustManager(private val pin: ByteArray) : X509TrustManager {

    fun matches(cert: X509Certificate): Boolean =
        MessageDigest.isEqual(Fingerprint.sha256(cert.encoded), pin)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Kein Server-Zertifikat")
        if (!matches(leaf)) {
            throw CertificateException(
                "Zertifikat-Fingerprint stimmt nicht überein (Server: ${Fingerprint.format(Fingerprint.sha256(leaf.encoded))})"
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client-Zertifikate werden nicht unterstützt")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Fingerprint {

    /** Accepts "AB:CD:…", "ab cd …" or plain hex. Returns null unless it is exactly 32 bytes. */
    fun parse(input: String): ByteArray? {
        val hex = input.filterNot { it == ':' || it.isWhitespace() }
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun format(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }
}
