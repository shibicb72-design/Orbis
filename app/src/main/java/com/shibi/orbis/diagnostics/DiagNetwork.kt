package com.shibi.orbis.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

enum class ConnectionKind(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("mobile data"),
    OTHER("other"),
    NONE("none"),
}

/** The phone's connection kind while recording is on. [start] reports the current kind, then every change. */
interface DiagNetworkSource {
    fun start(onChange: (ConnectionKind) -> Unit)

    fun stop()
}

fun connectionKindOf(caps: NetworkCapabilities?): ConnectionKind =
    when {
        caps == null -> ConnectionKind.NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionKind.MOBILE
        else -> ConnectionKind.OTHER
    }

/**
 * ConnectivityManager's default-network callback, registered only between [start] and [stop] (ACCESS_NETWORK_STATE,
 * which Orbis already has). Reports on the main thread.
 */
class AndroidDiagNetwork(private val context: Context) : DiagNetworkSource {
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onChange: (ConnectionKind) -> Unit) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(connectionKindOf(caps))

                override fun onLost(network: Network) = onChange(ConnectionKind.NONE)
            }
        if (runCatching { cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper())) }.isSuccess) {
            callback = cb
            // A phone with no network at all never calls back.
            if (cm.activeNetwork == null) onChange(ConnectionKind.NONE)
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }
}
