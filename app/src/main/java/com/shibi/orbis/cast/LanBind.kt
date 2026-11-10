package com.shibi.orbis.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.MulticastSocket
import java.net.Socket

/**
 * Binds a socket to Wi-Fi, or Ethernet when there is no Wi-Fi, so a VPN does not carry SSDP, SOAP, or a DIAL call.
 * No-op when neither transport is up.
 */
internal object LanBind {
    fun bind(context: Context, socket: Socket) {
        val network = lanNetwork(context) ?: return
        runCatching { network.bindSocket(socket) }
    }

    fun bind(context: Context, socket: MulticastSocket) {
        val network = lanNetwork(context) ?: return
        runCatching { network.bindSocket(socket) }
    }

    /** Wi-Fi, or Ethernet when Wi-Fi is down. Discovery uses the same choice so a VPN is not scanned. */
    internal fun lanNetwork(context: Context): Network? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        fun find(transport: Int): Network? =
            cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(transport) == true
            }
        return find(NetworkCapabilities.TRANSPORT_WIFI) ?: find(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
