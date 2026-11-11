package com.shibi.orbis.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkKind {
    NONE,

    /** Wi-Fi or Ethernet: what "Wi-Fi only" allows. */
    WIFI_OR_ETHERNET,
    OTHER,
}

/** The default network as torrents see it. [TorrentManager] pauses running torrents (WAITING_FOR_WIFI) when it is not allowed. */
interface TorrentNetwork {
    val kind: StateFlow<NetworkKind>
}

/** [TorrentNetwork] from ConnectivityManager's default-network callback. Registered once for the process. */
class AndroidTorrentNetwork(context: Context) : TorrentNetwork {
    private val _kind = MutableStateFlow(NetworkKind.NONE)
    override val kind: StateFlow<NetworkKind> = _kind.asStateFlow()

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            runCatching { _kind.value = kindOf(cm.getNetworkCapabilities(cm.activeNetwork)) }
            runCatching {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            _kind.value = kindOf(caps)
                        }

                        override fun onLost(network: Network) {
                            _kind.value = NetworkKind.NONE
                        }
                    },
                )
            }
        }
    }

    private fun kindOf(caps: NetworkCapabilities?): NetworkKind =
        when {
            caps == null -> NetworkKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkKind.WIFI_OR_ETHERNET
            else -> NetworkKind.OTHER
        }
}
