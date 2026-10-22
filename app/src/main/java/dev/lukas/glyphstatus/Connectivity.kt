package dev.lukas.glyphstatus

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
