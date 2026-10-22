package dev.lukas.glyphstatus

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
    /** Usage of the fullest available storage, 0..100. */
    val diskPercent: Int? = null,
)

object ProxmoxClient {

    private const val TIMEOUT_SECONDS = 5L

    /**
     * Blocking. Must not be called on the main thread.
     * UP only if the request succeeds and every node reports "status": "online".
     * When UP, guests and storage are fetched as well; their failure does not affect UP.
     */
    fun check(config: ServerConfig): CheckResult {
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
        fun request(path: String) = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .header("Authorization", auth)
            .get()
            .build()

        val nodesRequest = try {
            request("api2/json/nodes")
        } catch (e: IllegalArgumentException) {
            return CheckResult(false, "Ungültige Zeichen in Token-ID oder Secret")
        }

        try {
            val status = try {
                client.newCall(nodesRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return CheckResult(false, "HTTP ${response.code} ${response.message}".trim())
                    }
                    evaluateNodes(response.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                return CheckResult(false, "${e.javaClass.simpleName}: ${e.message ?: "Verbindungsfehler"}")
            }
            if (!status.up) return status

            val resources = try {
                client.newCall(request("api2/json/cluster/resources")).execute().use { response ->
                    if (response.isSuccessful) parseResources(response.body?.string().orEmpty()) else null
                }
            } catch (e: Exception) {
                null
            }
            if (resources == null) {
                return status.copy(message = "${status.message} · VMs/LXC/Storage nicht lesbar")
            }
            val diskText = resources.diskPercent?.let { " · Disk $it %" }.orEmpty()
            return status.copy(
                message = "${status.message} · ${resources.guestsRunning}/${resources.guestsTotal} VMs/LXC laufen$diskText",
                guestsRunning = resources.guestsRunning,
                guestsTotal = resources.guestsTotal,
                diskPercent = resources.diskPercent,
            )
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private class Resources(val guestsRunning: Int, val guestsTotal: Int, val diskPercent: Int?)

    /** Counts VMs and LXC containers (ignoring templates) and finds the fullest available storage. */
    private fun parseResources(body: String): Resources? {
        val items = try {
            JSONObject(body).getJSONArray("data")
        } catch (e: Exception) {
            return null
        }
        var running = 0
        var total = 0
        var disk: Int? = null
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            when (item.optString("type")) {
                "qemu", "lxc" -> {
                    if (item.optInt("template", 0) == 1) continue
                    total++
                    if (item.optString("status") == "running") running++
                }
                "storage" -> {
                    val max = item.optLong("maxdisk", 0)
                    if (item.optString("status") != "available" || max <= 0) continue
                    val percent = percent(item.optLong("disk", 0).toDouble() / max)
                    disk = maxOf(disk ?: 0, percent)
                }
            }
        }
        return Resources(running, total, disk)
    }

    private fun percent(fraction: Double): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

    private fun evaluateNodes(body: String): CheckResult {
        val nodes = try {
            JSONObject(body).getJSONArray("data")
        } catch (e: Exception) {
            return CheckResult(false, "Unerwartete Antwort: ${e.message}")
        }
        if (nodes.length() == 0) return CheckResult(false, "Keine Nodes in der Antwort")

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
            // uptime/cpu/mem are only present when the token has Sys.Audit on the node.
            if (node.has("uptime")) {
                val seconds = node.optLong("uptime")
                uptime = uptime?.let { minOf(it, seconds) } ?: seconds
            }
            if (node.has("cpu")) cpu = maxOf(cpu ?: 0, percent(node.optDouble("cpu", 0.0)))
            val maxMem = node.optLong("maxmem", 0)
            if (node.has("mem") && maxMem > 0) mem = maxOf(mem ?: 0, percent(node.optLong("mem").toDouble() / maxMem))
        }
        return if (offline.isEmpty()) {
            val details = buildString {
                uptime?.let { append(" · Uptime ${it / 86_400} T ${it % 86_400 / 3_600} h") }
                cpu?.let { append(" · CPU $it %") }
                mem?.let { append(" · RAM $it %") }
                if (uptime == null) append(" · keine Uptime/CPU/RAM – Token braucht PVEAuditor (Sys.Audit)")
            }
            CheckResult(
                true,
                "${nodes.length()} Node(s) online$details",
                uptimeSeconds = uptime,
                cpuPercent = cpu,
                memPercent = mem,
            )
        } else {
            CheckResult(false, "Nicht online: ${offline.joinToString()}")
        }
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
