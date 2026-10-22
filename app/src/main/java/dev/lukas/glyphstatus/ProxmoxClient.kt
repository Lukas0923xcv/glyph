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

data class CheckResult(val up: Boolean, val message: String)

object ProxmoxClient {

    private const val TIMEOUT_SECONDS = 5L

    /**
     * Blocking. Must not be called on the main thread.
     * UP only if the request succeeds and every node reports "status": "online".
     */
    fun check(config: ServerConfig): CheckResult {
        val base = config.host.trim().trimEnd('/').toHttpUrlOrNull()
            ?: return CheckResult(false, "Ungültige Host-URL")
        if (config.tokenId.isBlank() || config.secret.isBlank()) {
            return CheckResult(false, "Token-ID oder Secret fehlt")
        }
        val url = base.newBuilder().addPathSegments("api2/json/nodes").build()

        val client = try {
            buildClient(config.fingerprint)
        } catch (e: IllegalArgumentException) {
            return CheckResult(false, e.message ?: "Ungültiger Fingerprint")
        }

        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "PVEAPIToken=${config.tokenId.trim()}=${config.secret.trim()}")
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            return CheckResult(false, "Ungültige Zeichen in Token-ID oder Secret")
        }

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return CheckResult(false, "HTTP ${response.code} ${response.message}".trim())
                }
                evaluate(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            CheckResult(false, "${e.javaClass.simpleName}: ${e.message ?: "Verbindungsfehler"}")
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private fun evaluate(body: String): CheckResult {
        val nodes = try {
            JSONObject(body).getJSONArray("data")
        } catch (e: Exception) {
            return CheckResult(false, "Unerwartete Antwort: ${e.message}")
        }
        if (nodes.length() == 0) return CheckResult(false, "Keine Nodes in der Antwort")

        val offline = mutableListOf<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optString("status") != "online") {
                offline += "${node.optString("node", "?")} (${node.optString("status", "unbekannt")})"
            }
        }
        return if (offline.isEmpty()) {
            CheckResult(true, "${nodes.length()} Node(s) online")
        } else {
            CheckResult(false, "Nicht online: ${offline.joinToString()}")
        }
    }

    private fun buildClient(fingerprint: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

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
