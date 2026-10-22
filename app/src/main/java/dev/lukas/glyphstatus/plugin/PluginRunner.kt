package dev.lukas.glyphstatus.plugin

import dev.lukas.glyphstatus.ProxmoxClient
import dev.lukas.glyphstatus.ServerConfig
import okhttp3.Request
import org.json.JSONTokener
import java.io.IOException

/** Result of fetching one plugin's source. */
data class PluginFetch(val values: Map<String, Any?>, val error: String?)

/** Fetches plugin sources. Blocking; call off the main thread. */
object PluginRunner {

    /**
     * @param secrets values for the plugin's declared secrets
     * @param node first online Proxmox node, substituted for {node} in proxmox paths
     */
    fun fetch(plugin: Plugin, secrets: Map<String, String>, proxmox: ServerConfig?, node: String?): PluginFetch {
        return try {
            when (val source = plugin.source) {
                Source.Builtin -> PluginFetch(emptyMap(), null)
                is Source.Proxmox -> {
                    val config = proxmox ?: return PluginFetch(emptyMap(), "Proxmox ist nicht eingerichtet")
                    if ("{node}" in source.path && node == null) return PluginFetch(emptyMap(), "Kein Node online")
                    val body = ProxmoxClient.get(config, source.path.replace("{node}", node.orEmpty()))
                    PluginFetch(PluginEngine.extract(plugin, parse(body, text = false)), null)
                }
                is Source.Http -> {
                    val missing = plugin.secrets.map { it.key }.filter { secrets[it].isNullOrEmpty() }
                    if (missing.isNotEmpty()) return PluginFetch(emptyMap(), "Secret fehlt: ${missing.joinToString()}")
                    val body = http(source, secrets)
                    PluginFetch(PluginEngine.extract(plugin, parse(body, source.text)), null)
                }
            }
        } catch (e: Exception) {
            PluginFetch(emptyMap(), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun http(source: Source.Http, secrets: Map<String, String>): String {
        fun substitute(text: String) = PluginParser.SECRET.replace(text) { secrets[it.groupValues[1]].orEmpty() }
        val request = Request.Builder().url(substitute(source.url)).get().apply {
            source.headers.forEach { (name, value) -> header(name, substitute(value)) }
        }.build()
        val client = ProxmoxClient.buildClient(source.fingerprint)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                return response.body?.string().orEmpty()
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /** JSON object, array or bare JSON value; with [text] the trimmed body as a string. */
    private fun parse(body: String, text: Boolean): Any? {
        if (text) return body.trim()
        return JSONTokener(body).nextValue()
    }
}
