package com.shibi.orbis.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal data class CastLinkStatus(
    val playing: Boolean,
    val positionSec: Double,
    val durationSec: Double,
    val volume: Float,
)

/**
 * One Chromecast session over CASTV2, plus the mDNS list that feeds the sheet.
 * The TLS trust decision exists only for this socket and only after the address has been checked to be a private LAN
 * host. It is not installed as the process trust manager.
 */
internal class ChromecastHub(context: Context) {
    private val appContext = context.applicationContext
    private val discovery = ChromecastDiscovery(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val dials =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-dial").apply { isDaemon = true }
        }
    private var live: Live? = null
    private var generation = 0

    var onDevicesChanged: (() -> Unit)? = null
    var onStatus: ((CastLinkStatus) -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null

    init {
        discovery.onChanged = { main.post { onDevicesChanged?.invoke() } }
    }

    fun devices(): List<ChromecastDevice> = discovery.devices()

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun cast(
        deviceId: String,
        goal: CastGoal,
        onReady: () -> Unit,
        onFailed: (CastFailure) -> Unit,
    ) {
        val device = devices().firstOrNull { it.id == deviceId }
        if (device == null) {
            onFailed(CastFailure.GENERIC)
            return
        }
        disconnect()
        val epoch = synchronized(this) { generation += 1; generation }
        val machine = CastSession(goal)
        val inbox = ConcurrentLinkedQueue<() -> Unit>()
        Thread({
            if (!isCurrent(epoch)) return@Thread
            val socket = openCastSocket(device.address) { LanBind.bind(appContext, it) }
            if (socket == null || !isCurrent(epoch)) {
                runCatching { socket?.close() }
                if (isCurrent(epoch)) main.post { onFailed(CastFailure.GENERIC) }
                return@Thread
            }
            val session = Live(epoch, device, goal, machine, socket, inbox)
            synchronized(this) {
                if (!isCurrent(epoch)) {
                    runCatching { socket.close() }
                    return@Thread
                }
                live = session
            }
            var ready = false
            try {
                apply(session, machine.start(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                while (isCurrent(epoch) && !session.localStop) {
                    drain(session)
                    if (!isCurrent(epoch) || session.localStop) break
                    when (val read = readFrame(socket)) {
                        Read.Tick -> apply(session, machine.onClock(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                        Read.Closed -> break
                        is Read.Frame ->
                            apply(
                                session,
                                machine.onFrame(
                                    CastFrame(read.message.sourceId, read.message.destinationId, read.message.namespace, read.message.payloadUtf8),
                                    System.currentTimeMillis(),
                                ),
                                onReady,
                                onFailed,
                            ) { ready = true }
                    }
                }
            } catch (_: Exception) {
                if (isCurrent(epoch) && !session.localStop && !ready) main.post { onFailed(CastFailure.GENERIC) }
            } finally {
                runCatching { socket.close() }
                val ended = synchronized(this) {
                    val current = live === session
                    if (current) live = null
                    current && ready && !session.localStop
                }
                if (ended) main.post { onSessionEnded?.invoke() }
            }
        }, "orbis-cast").apply { isDaemon = true }.start()
    }

    fun play() = command { it.play() }

    fun pause() = command { it.pause() }

    fun seek(positionSec: Double) = command { it.seek(positionSec) }

    fun setVolume(level: Float) = command { it.setVolume(level) }

    fun volume(): Float = synchronized(this) { live?.machine?.volume() ?: 1f }

    fun stop() = command { it.stopMedia() }

    fun disconnect() {
        val session = synchronized(this) {
            generation += 1
            live.also { live = null }
        } ?: return
        session.localStop = true
        val effects = synchronized(session.machine) { session.machine.close() }
        write(session, effects.filterIsInstance<CastEffect.Send>().map { it.frame })
        if (session.goal is CastGoal.YouTube) dialDelete(session.device.address)
        runCatching { session.socket.close() }
    }

    fun hasSession(): Boolean = synchronized(this) { live != null }

    private fun command(block: (CastSession) -> List<CastEffect>) {
        val session = synchronized(this) { live } ?: return
        session.inbox.add {
            val effects = synchronized(session.machine) { block(session.machine) }
            write(session, effects.filterIsInstance<CastEffect.Send>().map { it.frame })
        }
    }

    private fun drain(session: Live) {
        while (true) {
            val task = session.inbox.poll() ?: return
            task()
            if (!isCurrent(session.epoch)) return
        }
    }

    private fun apply(
        session: Live,
        effects: List<CastEffect>,
        onReady: () -> Unit,
        onFailed: (CastFailure) -> Unit,
        markReady: () -> Unit,
    ) {
        val sends = ArrayList<CastFrame>()
        for (effect in effects) {
            when (effect) {
                is CastEffect.Send -> sends += effect.frame
                is CastEffect.DialPost -> dial(session, effect.body)
                CastEffect.DialDelete -> dialDelete(session.device.address)
                is CastEffect.Ready -> {
                    markReady()
                    if (isCurrent(session.epoch)) main.post { onReady() }
                }
                is CastEffect.Failed -> if (isCurrent(session.epoch) && !session.localStop) main.post { onFailed(effect.reason) }
                is CastEffect.Progress ->
                    if (isCurrent(session.epoch)) {
                        main.post {
                            onStatus?.invoke(
                                CastLinkStatus(effect.playing, effect.positionSec, effect.durationSec, effect.volume),
                            )
                        }
                    }
            }
        }
        write(session, sends)
    }

    private fun dial(session: Live, body: String) {
        val host = dialHost(session.device.address) ?: run {
            session.inbox.add { apply(session, synchronized(session.machine) { session.machine.onDial(null, System.currentTimeMillis()) }, {}, {}, {}) }
            return
        }
        val epoch = session.epoch
        dials.execute {
            if (!isCurrent(epoch)) return@execute
            val response =
                lanExchange(
                    method = "POST",
                    url = "http://$host:8008/apps/YouTube",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = body,
                    bind = { socket -> LanBind.bind(appContext, socket) },
                )
            if (!isCurrent(epoch)) return@execute
            session.inbox.add {
                apply(
                    session,
                    synchronized(session.machine) { session.machine.onDial(response?.code, System.currentTimeMillis()) },
                    {},
                    {},
                    {},
                )
            }
        }
    }

    private fun dialDelete(address: InetAddress) {
        val host = dialHost(address) ?: return
        dials.execute {
            lanExchange(
                method = "DELETE",
                url = "http://$host:8008/apps/YouTube",
                bind = { socket -> LanBind.bind(appContext, socket) },
            )
        }
    }

    private fun write(session: Live, frames: List<CastFrame>) {
        if (frames.isEmpty()) return
        synchronized(session.socket) {
            val out = session.socket.outputStream
            for (frame in frames) {
                out.write(frameCastMessage(CastMessage(frame.sourceId, frame.destinationId, frame.namespace, frame.payload)))
            }
            out.flush()
        }
    }

    private fun isCurrent(epoch: Int): Boolean = synchronized(this) { epoch == generation }

    private class Live(
        val epoch: Int,
        val device: ChromecastDevice,
        val goal: CastGoal,
        val machine: CastSession,
        val socket: SSLSocket,
        val inbox: ConcurrentLinkedQueue<() -> Unit>,
    ) {
        @Volatile var localStop: Boolean = false
    }
}

private sealed interface Read {
    data class Frame(val message: CastMessage) : Read
    data object Tick : Read
    data object Closed : Read
}

/**
 * TLS to a discovered LAN host on the Cast port. The trust manager accepts the device certificate for this socket
 * only. [bind] attaches the socket to Wi-Fi before connect. Returns null for any address that is not a private LAN host.
 */
internal fun openCastSocket(address: InetAddress, bind: (Socket) -> Unit): SSLSocket? {
    if (!isLocalLanAddress(address)) return null
    var socket: SSLSocket? = null
    return try {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(LanCastTrust()), SecureRandom())
        socket = context.socketFactory.createSocket() as SSLSocket
        bind(socket)
        socket.connect(InetSocketAddress(address, CAST_PORT), 4_000)
        val protocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (protocols.isNotEmpty()) socket.enabledProtocols = protocols.toTypedArray()
        socket.soTimeout = 4_000
        socket.keepAlive = true
        socket.startHandshake()
        socket.soTimeout = 400
        socket
    } catch (_: Exception) {
        runCatching { socket?.close() }
        null
    }
}

private fun readFrame(socket: SSLSocket): Read {
    return try {
        val header = readFully(socket.inputStream, 4) ?: return Read.Closed
        val length = castFrameLength(header) ?: return Read.Closed
        if (length < 0) return Read.Closed
        val body = readFully(socket.inputStream, length) ?: return Read.Closed
        val message = decodeCastMessage(body) ?: return Read.Closed
        Read.Frame(message)
    } catch (_: SocketTimeoutException) {
        Read.Tick
    } catch (_: Exception) {
        Read.Closed
    }
}

private fun readFully(input: InputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read =
            try {
                input.read(buffer, offset, size - offset)
            } catch (timeout: SocketTimeoutException) {
                if (offset == 0) throw timeout
                continue
            }
        if (read < 0) return null
        offset += read
    }
    return buffer
}

private fun dialHost(address: InetAddress): String? {
    if (!isLocalLanAddress(address)) return null
    val raw = address.hostAddress ?: return null
    return if (raw.contains(':')) "[${raw.substringBefore('%')}]" else raw
}

/** Trusts the peer certificate of one Cast socket. Not installed on the default SSL context. */
private class LanCastTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Cast does not authenticate this phone to the TV")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Cast device presented no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
