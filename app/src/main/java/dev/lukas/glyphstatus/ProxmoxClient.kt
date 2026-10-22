package dev.lukas.glyphstatus

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
)

object ProxmoxClient {

    private const val TIMEOUT_SECONDS = 5L

    /**
     * Blocking. Must not be called on the main thread.
     * UP only if the request succeeds and every node reports "status": "online".
     * When UP, the data for the [views] that need extra requests is fetched as well;
     * a failing extra request only leaves that value empty and does not affect UP.
     */
    fun check(config: ServerConfig, views: Set<Screens.View> = Screens.View.entries.toSet()): CheckResult {
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

        /** GET returning the "data" field, or null on any error. */
        fun fetch(path: String, vararg query: Pair<String, String>): Any? = try {
            client.newCall(request(path, *query)).execute().use { response ->
                if (response.isSuccessful) JSONObject(response.body?.string().orEmpty()).opt("data") else null
            }
        } catch (e: Exception) {
            null
        }

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
                return CheckResult(false, "${e.javaClass.simpleName}: ${e.message ?: "Verbindungsfehler"}")
            }
            var result = nodes.result
            if (!result.up) return result
            val details = StringBuilder(result.message)

            if (Screens.View.GUESTS in views || Screens.View.DISK in views) {
                val resources = (fetch("api2/json/cluster/resources") as? JSONArray)?.let(::parseResources)
                if (resources == null) {
                    details.append(" · VMs/LXC/Storage nicht lesbar")
                } else {
                    details.append(" · ${resources.guestsRunning}/${resources.guestsTotal} VMs/LXC laufen")
                    resources.diskPercent?.let { details.append(" · Disk $it %") }
                    result = result.copy(
                        guestsRunning = resources.guestsRunning,
                        guestsTotal = resources.guestsTotal,
                        diskPercent = resources.diskPercent,
                    )
                }
            }

            if (Screens.View.LOAD in views) {
                val loads = nodes.online.mapNotNull { node ->
                    ((fetch("api2/json/nodes/$node/status") as? JSONObject)?.optJSONArray("loadavg"))
                        ?.optString(0)?.toDoubleOrNull()
                }
                loads.maxOrNull()?.let {
                    details.append(" · Load $it")
                    result = result.copy(loadAverage = it)
                }
            }

            if (Screens.View.NET in views) {
                val perNode = nodes.online.map { node ->
                    (fetch("api2/json/nodes/$node/rrddata", "timeframe" to "hour", "cf" to "AVERAGE") as? JSONArray)
                        ?.let(::latestTrafficBytesPerSecond)
                }
                if (perNode.isNotEmpty() && perNode.all { it != null }) {
                    val mbit = (perNode.sumOf { it!! } * 8 / 1_000_000).roundToInt()
                    details.append(" · Netz $mbit Mbit/s")
                    result = result.copy(netMbit = mbit)
                }
            }

            if (Screens.View.BACKUP in views) {
                (fetch("api2/json/cluster/tasks") as? JSONArray)?.let(::lastBackupOk)?.let { ok ->
                    details.append(if (ok) " · letztes Backup OK" else " · letztes Backup FEHLGESCHLAGEN")
                    result = result.copy(backupOk = ok)
                }
            }

            return result.copy(message = details.toString())
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private class Resources(val guestsRunning: Int, val guestsTotal: Int, val diskPercent: Int?)

    /**
     * Counts VMs and LXC containers (ignoring templates) and combines the used space of all
     * available storages that hold VM disks or container volumes (all available storages if none
     * declares that content). Shared storages are listed once per node, so they are counted once.
     */
    private fun parseResources(items: JSONArray): Resources {
        var running = 0
        var total = 0
        val guestStorages = mutableMapOf<String, Pair<Long, Long>>()
        val allStorages = mutableMapOf<String, Pair<Long, Long>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "qemu", "lxc" -> {
                    if (item.optInt("template", 0) == 1) continue
                    total++
                    if (item.optString("status") == "running") running++
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
        return Resources(running, total, disk)
    }

    /** netin + netout of the newest rrd sample that has them, in bytes per second. */
    private fun latestTrafficBytesPerSecond(samples: JSONArray): Double? {
        for (i in samples.length() - 1 downTo 0) {
            val sample = samples.optJSONObject(i) ?: continue
            if (sample.has("netin") && sample.has("netout")) {
                return sample.optDouble("netin", 0.0) + sample.optDouble("netout", 0.0)
            }
        }
        return null
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

    private fun buildClient(fingerprint: String): OkHttpClient {
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
