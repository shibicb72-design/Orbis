package com.shibi.orbis.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import java.util.concurrent.Executors

/**
 * Finds Chromecast, Google TV and Nest devices by mDNS (`_googlecast._tcp`) on Wi-Fi.
 * Android 14 and later resolve through [NsdManager.ServiceInfoCallback]. Older releases resolve one service at a time,
 * which is all [NsdManager.resolveService] allows. Nothing here contacts Google.
 */
internal class ChromecastDiscovery(context: Context, private val log: (String) -> Unit = {}) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-nsd").apply { isDaemon = true }
        }
    private val devices = LinkedHashMap<String, ChromecastDevice>()
    private val callbacks = LinkedHashMap<String, NsdManager.ServiceInfoCallback>()
    private val resolveQueue = ArrayDeque<android.net.nsd.NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var running = false
    private var lock: WifiManager.MulticastLock? = null
    /** One "list full" line per discovery run. */
    private var fullLogged = false

    var onChanged: (() -> Unit)? = null

    fun devices(): List<ChromecastDevice> = synchronized(devices) { devices.values.toList() }

    fun start() {
        if (nsd == null || running) return
        running = true
        fullLogged = false
        lock = multicastLock()
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    running = false
                    log("discovery cast error=NsdFailed code=$errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    if (!running) return
                    if (usable(serviceInfo)) accept(serviceInfo)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        watch(serviceInfo)
                    } else {
                        enqueueResolve(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    remove(serviceInfo.serviceName.orEmpty())
                }
            }
        listener = discovery
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val network = LanBind.lanNetwork(appContext)
                if (network != null) {
                    nsd.discoverServices(CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, executor, discovery)
                    return
                }
            }
            nsd.discoverServices(CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: SecurityException) {
            log("discovery cast error=${errName(e)}")
            running = false
            releaseLock()
        }
    }

    fun stop() {
        running = false
        val current = listener
        listener = null
        if (current != null && nsd != null) runCatching { nsd.stopServiceDiscovery(current) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = synchronized(callbacks) { callbacks.values.toList().also { callbacks.clear() } }
            pending.forEach { callback -> runCatching { nsd?.unregisterServiceInfoCallback(callback) } }
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        synchronized(devices) { devices.clear() }
        releaseLock()
        onChanged?.invoke()
    }

    @SuppressLint("NewApi")
    private fun watch(serviceInfo: android.net.nsd.NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nsd == null) return
        val name = serviceInfo.serviceName ?: return
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() = remove(name)
                override fun onServiceUpdated(updated: android.net.nsd.NsdServiceInfo) {
                    if (running) accept(updated)
                }
            }
        synchronized(callbacks) {
            callbacks.remove(name)?.let { previous -> runCatching { nsd.unregisterServiceInfoCallback(previous) } }
            callbacks[name] = callback
        }
        runCatching { nsd.registerServiceInfoCallback(serviceInfo, executor, callback) }
    }

    private fun enqueueResolve(serviceInfo: android.net.nsd.NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(serviceInfo)
            if (!resolving) resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val nsd = nsd ?: return
        while (running) {
            val next = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() } ?: break
            resolving = true
            try {
                nsd.resolveService(
                    next,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) = finish()
                        override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                            if (running) accept(serviceInfo)
                            finish()
                        }
                        private fun finish() {
                            synchronized(resolveQueue) { resolveNext() }
                        }
                    },
                )
                return
            } catch (_: Exception) {
                // This service could not be queued. Try the next one.
            }
        }
        resolving = false
    }

    private fun accept(info: android.net.nsd.NsdServiceInfo) {
        val hosts =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses.orEmpty().ifEmpty { listOfNotNull(info.host) }
            } else {
                listOfNotNull(info.host)
            }
        val device = castDeviceFromTxt(info.attributes ?: emptyMap(), info.serviceName.orEmpty(), hosts) ?: return
        var added = false
        var full = false
        val changed = synchronized(devices) {
            val previous = devices[device.id]
            if (previous == null && devices.size >= CAST_DEVICE_LIST_MAX) {
                full = true
                return@synchronized false
            }
            devices[device.id] = device
            added = previous == null
            previous != device
        }
        if (full && !fullLogged) {
            fullLogged = true
            log("discovery cast list full")
        }
        if (added) log("discovery cast found dev=${deviceTag(device.id)} model=\"${logModel(device.model)}\"")
        if (changed) onChanged?.invoke()
    }

    private fun usable(info: android.net.nsd.NsdServiceInfo): Boolean {
        val hosts =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        return hosts.any { isLocalLanAddress(it) }
    }

    private fun remove(serviceName: String) {
        if (serviceName.isBlank()) return
        val removed = synchronized(devices) {
            val id = devices.entries.firstOrNull { it.value.serviceName == serviceName }?.key
            if (id != null) devices.remove(id)
            id
        }
        if (removed != null) {
            log("discovery cast lost dev=${deviceTag(removed)}")
            onChanged?.invoke()
        }
    }

    private fun multicastLock(): WifiManager.MulticastLock? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        return try {
            wifi.createMulticastLock("orbis-cast-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseLock() {
        val held = lock
        lock = null
        if (held?.isHeld == true) runCatching { held.release() }
    }
}

/** A further distinct id is ignored once this many Chromecasts are listed. */
internal const val CAST_DEVICE_LIST_MAX = 32
