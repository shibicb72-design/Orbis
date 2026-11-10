package com.shibi.orbis.cast

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class DlnaPlayback(val positionSec: Double, val durationSec: Double, val playing: Boolean, val volume: Float)

/**
 * Finds MediaRenderer devices with SSDP and drives AVTransport. Discovery uses a multicast lock so Wi-Fi power saving
 * does not drop the replies. Nothing is written to disk.
 */
internal class DlnaClient(context: Context) {
    private val appContext = context.applicationContext

    fun discover(timeoutMs: Int = 5_000): List<DlnaRenderer> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val searchMs = (timeoutMs / 2).coerceIn(400, 2_500)
        val targets = selectDiscoveryTargets(ssdpSearch(searchMs))
        if (targets.isEmpty()) return emptyList()
        val pool =
            Executors.newFixedThreadPool(targets.size.coerceAtMost(8)) { runnable ->
                Thread(runnable, "orbis-dlna").apply { isDaemon = true }
            }
        try {
            val futures =
                targets.map { reply ->
                    pool.submit<DlnaRenderer?> {
                        if (System.currentTimeMillis() >= deadline) return@submit null
                        val xml = lanGet(reply.location, deadline) ?: return@submit null
                        parseDlnaDescription(xml, reply.location)
                    }
                }
            val found = ArrayList<DlnaRenderer>(targets.size)
            for (future in futures) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    future.cancel(true)
                    continue
                }
                val renderer =
                    try {
                        future.get(left, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                        future.cancel(true)
                        null
                    }
                if (renderer != null) found += renderer
            }
            return found
        } finally {
            pool.shutdownNow()
        }
    }

    fun play(renderer: DlnaRenderer, media: CastableMedia): Boolean {
        val didl = buildDidlLite(media)
        // A renderer that is already playing rejects a new URI until it is stopped.
        postSoap(renderer.avTransportUrl, soapStop())
        val set = postSoap(renderer.avTransportUrl, soapSetAvTransportUri(media.url, didl)) ?: return false
        if (set.code !in 200..299) return false
        val play = postSoap(renderer.avTransportUrl, soapPlay()) ?: return false
        if (play.code !in 200..299) return false
        if (media.positionSec >= 1.0) postSoap(renderer.avTransportUrl, soapSeek(media.positionSec))
        return true
    }

    fun pause(renderer: DlnaRenderer): Boolean = ok(postSoap(renderer.avTransportUrl, soapPause()))

    fun resume(renderer: DlnaRenderer): Boolean = ok(postSoap(renderer.avTransportUrl, soapPlay()))

    fun stop(renderer: DlnaRenderer): Boolean = ok(postSoap(renderer.avTransportUrl, soapStop()))

    fun seek(renderer: DlnaRenderer, positionSec: Double): Boolean =
        ok(postSoap(renderer.avTransportUrl, soapSeek(positionSec)))

    fun setVolume(renderer: DlnaRenderer, fraction: Float): Boolean {
        val url = renderer.renderingControlUrl ?: return false
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        return ok(postSoap(url, soapSetVolume(percent)))
    }

    fun poll(renderer: DlnaRenderer): DlnaPlayback? {
        val positionXml = postSoap(renderer.avTransportUrl, soapGetPositionInfo())?.body ?: return null
        val position = parsePositionInfo(positionXml) ?: return null
        val state = postSoap(renderer.avTransportUrl, soapGetTransportInfo())?.body?.let(::parseTransportState)
        val volume =
            renderer.renderingControlUrl?.let { url ->
                postSoap(url, soapGetVolume())?.body?.let(::parseVolume)?.div(100f)
            }
        val playing = state == "PLAYING" || state == "TRANSITIONING"
        return DlnaPlayback(position.positionSec, position.durationSec, playing, volume ?: -1f)
    }

    private fun ok(exchange: LanExchange?): Boolean = exchange?.code in 200..299

    private fun lanGet(url: String, deadlineMs: Long): String? {
        val response = lanExchange("GET", url, deadlineMs = deadlineMs, bind = ::bindLan) ?: return null
        if (response.code !in 200..299) return null
        return response.body
    }

    private fun postSoap(controlUrl: String, call: SoapCall): LanExchange? =
        lanExchange(
            method = "POST",
            url = controlUrl,
            headers =
                mapOf(
                    "Content-Type" to "text/xml; charset=\"utf-8\"",
                    "SOAPAction" to call.soapAction,
                ),
            body = call.body,
            bind = ::bindLan,
        )

    private fun ssdpSearch(timeoutMs: Int): List<SsdpHit> {
        val lock = multicastLock()
        val socket = MulticastSocket(null)
        try {
            socket.reuseAddress = true
            socket.timeToLive = 2
            bindLan(socket)
            socket.bind(InetSocketAddress(0))
            val group = InetAddress.getByName(SSDP_MULTICAST)
            runCatching { socket.joinGroup(InetSocketAddress(group, SSDP_PORT), socket.networkInterface) }
            val targets = listOf(SSDP_MEDIA_RENDERER, SSDP_AV_TRANSPORT)
            for (target in targets) {
                val bytes = ssdpSearch(target).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(group, SSDP_PORT)))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val found = ArrayList<SsdpHit>()
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val source = packet.address ?: continue
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                parseSsdpResponse(text)?.let { found += SsdpHit(it, source) }
            }
            return found
        } catch (_: Exception) {
            return emptyList()
        } finally {
            runCatching { socket.close() }
            if (lock?.isHeld == true) runCatching { lock.release() }
        }
    }

    private fun multicastLock(): WifiManager.MulticastLock? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        return try {
            wifi.createMulticastLock("orbis-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Prefer Wi-Fi (then Ethernet) so a VPN does not swallow SSDP or the SOAP connection to the TV. */
    private fun bindLan(socket: java.net.Socket) {
        LanBind.bind(appContext, socket)
    }

    private fun bindLan(socket: MulticastSocket) {
        LanBind.bind(appContext, socket)
    }
}
