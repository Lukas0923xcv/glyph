package dev.lukas.glyphstatus

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Tells "the phone cannot reach anything" apart from "the server does not answer". */
object Connectivity {

    /** True if [host] is a Tailscale address (100.64.0.0/10) or MagicDNS name (*.ts.net). */
    fun expectsVpn(host: String): Boolean {
        val name = host.trim().toHttpUrlOrNull()?.host ?: return false
        if (name.endsWith(".ts.net")) return true
        val octets = name.split('.').mapNotNull { it.toIntOrNull() }
        return octets.size == 4 && octets[0] == 100 && octets[1] in 64..127
    }

    /** One line for the check log: network, VPN and battery optimization as Android reports them. */
    fun describe(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val network = when {
            capabilities == null -> "kein Netz für die App"
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "Netz ohne Internet"
            else -> "Netz ok"
        }
        val vpn = if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "VPN an" else "VPN aus"
        val battery = if (ignoresBatteryOptimization(context)) "Akku-Optimierung aus" else "Akku-Optimierung AN"
        return "$network, $vpn, $battery"
    }

    fun ignoresBatteryOptimization(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Why a server that could not be reached counts as down. */
    fun downReason(context: Context, host: String): Screens.DownReason {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?: return Screens.DownReason.NO_NETWORK
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Screens.DownReason.NO_NETWORK
        if (expectsVpn(host) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return Screens.DownReason.VPN_DOWN
        }
        return Screens.DownReason.SERVER
    }
}
