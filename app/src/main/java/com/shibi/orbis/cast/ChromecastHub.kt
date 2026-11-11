package com.shibi.orbis.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shibi.orbis.youtube.HttpsLoungeTransport
import com.shibi.orbis.youtube.LoungeClient
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
 * host. It is not installed as the process trust manager. The TV is identified by its deviceauth key, pinned per device
 * (see [openCastSocket]).
 * [log] receives secret-free "Cast" lines (see CastLogging.kt).
 */
internal class ChromecastHub(context: Context, private val log: (String) -> Unit = {}) {
    private val appContext = context.applicationContext
    private val discovery = ChromecastDiscovery(appContext, log)
    private val main = Handler(Looper.getMainLooper())
    private val dials =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-dial").apply { isDaemon = true }
        }
    private val sessionIo =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-io").apply { isDaemon = true }
        }
    private val lounges =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-lounge").apply { isDaemon = true }
        }
    private val loungeClient = LoungeClient(HttpsLoungeTransport(), log)
    private val pins = CastPins(PrefsCastPinStore(appContext))
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
        val machine = CastSession(goal, log = log)
        val inbox = ConcurrentLinkedQueue<() -> Unit>()
        val dev = deviceTag(device.id)
        Thread({
            if (!isCurrent(epoch)) return@Thread
            val connectStart = System.currentTimeMillis()
            val opened = openCastSocket(device.address, device.id, pins) { LanBind.bind(appContext, it) }
            val connectMs = System.currentTimeMillis() - connectStart
            when (opened) {
                CastSocketOpen.Untrusted -> log("connect dev=$dev untrusted")
                CastSocketOpen.AuthFailed -> log("connect dev=$dev deviceauth failed")
                CastSocketOpen.AuthNone -> log("connect dev=$dev deviceauth none")
                CastSocketOpen.Failed -> log("connect dev=$dev failed lan=${isLocalLanAddress(device.address)} ms=$connectMs")
                is CastSocketOpen.Opened ->
                    log("connect dev=$dev tls=ok deviceauth=ok pin=${opened.pin.name.lowercase()} ms=$connectMs")
            }
            val socket = (opened as? CastSocketOpen.Opened)?.socket
            if (socket == null || !isCurrent(epoch)) {
                runCatching { socket?.close() }
                val reason = if (opened == CastSocketOpen.Untrusted) CastFailure.UNTRUSTED else CastFailure.GENERIC
                if (isCurrent(epoch)) main.post { onFailed(reason) }
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
            var cause = "localStop"
            try {
                apply(session, machine.start(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                while (isCurrent(epoch) && !session.localStop) {
                    drain(session)
                    if (!isCurrent(epoch) || session.localStop) break
                    when (val read = readCastFrame(socket.inputStream)) {
                        Read.Tick -> apply(session, machine.onClock(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                        Read.Closed -> {
                            cause = "closed"
                            break
                        }
                        is Read.Frame -> {
                            logRx(session, read.message.namespace, read.message.payloadUtf8)
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
                }
                if (cause == "localStop" && !session.localStop) cause = "replaced"
            } catch (e: Exception) {
                cause = "error err=${errName(e)}"
                if (isCurrent(epoch) && !session.localStop && !ready) main.post { onFailed(CastFailure.GENERIC) }
            } finally {
                log("session ended cause=$cause dev=$dev ready=$ready")
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
        val closing = synchronized(session.machine) { session.machine.close() }
        val frames = closing.filterIsInstance<CastEffect.Send>().map { it.frame }
        val deleteDial = closing.any { it == CastEffect.DialDelete }
        log("disconnect dev=${deviceTag(session.device.id)} dialDelete=$deleteDial")
        if (deleteDial) dialDelete(session.device.address)
        sessionIo.execute {
            write(session, frames)
            runCatching { session.socket.close() }
        }
    }

    fun hasSession(): Boolean = synchronized(this) { live != null }

    /** Drops the identity-key pin for [deviceId]. The next cast to it pins the key it proves then. */
    fun forgetPin(deviceId: String) {
        pins.forget(deviceId)
        log("pin forgotten dev=${deviceTag(deviceId)}")
    }

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
                is CastEffect.Send -> {
                    sends += effect.frame
                    castTxLine(effect.frame.namespace, effect.frame.payload)?.let(log)
                }
                is CastEffect.DialPost -> dial(session, effect.body)
                is CastEffect.LoungePlay -> lounge(session, effect)
                CastEffect.DialDelete -> dialDelete(session.device.address)
                is CastEffect.Ready -> {
                    markReady()
                    if (isCurrent(session.epoch)) main.post { onReady() }
                }
                is CastEffect.Failed -> {
                    if (effect.reason == CastFailure.SILENCE) log("session ended cause=silence dev=${deviceTag(session.device.id)}")
                    if (isCurrent(session.epoch) && !session.localStop) main.post { onFailed(effect.reason) }
                }
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
            val started = System.currentTimeMillis()
            val response =
                lanExchange(
                    method = "POST",
                    url = "http://$host:8008/apps/YouTube",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = body,
                    bind = { socket -> LanBind.bind(appContext, socket) },
                )
            log("youtube step=dial http=${response?.code ?: "none"} ms=${System.currentTimeMillis() - started}")
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

    /** Lounge hand-over off the socket thread. The result goes back to the machine through the session inbox. */
    private fun lounge(session: Live, effect: CastEffect.LoungePlay) {
        val epoch = session.epoch
        lounges.execute {
            if (!isCurrent(epoch)) return@execute
            val result =
                loungeClient.play(effect.screenId, effect.videoId, effect.startSec) { isCurrent(epoch) }
                    ?: return@execute
            if (!isCurrent(epoch)) return@execute
            session.inbox.add {
                apply(
                    session,
                    synchronized(session.machine) { session.machine.onLounge(result, System.currentTimeMillis()) },
                    {},
                    {},
                    {},
                )
            }
        }
    }

    /** Receiver and error frames get a line; each YouTube-namespace message type gets one line per session. */
    private fun logRx(session: Live, namespace: String, payload: String) {
        castRxLine(namespace, payload)?.let(log)
        if (namespace == YOUTUBE_NAMESPACE) {
            val type = logToken(payloadType(payload)).ifEmpty { "unknown" }
            if (session.mdxTypes.add(type)) log("rx mdx type=$type")
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
        try {
            synchronized(session.socket) {
                val out = session.socket.outputStream
                for (frame in frames) {
                    out.write(frameCastMessage(CastMessage(frame.sourceId, frame.destinationId, frame.namespace, frame.payload)))
                }
                out.flush()
            }
        } catch (_: Exception) {
            // The TV may already have closed the socket. The reader notices on its next read.
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
        val mdxTypes = HashSet<String>()
    }
}

internal sealed interface Read {
    data class Frame(val message: CastMessage) : Read
    data object Tick : Read
    data object Closed : Read
}

/** Once any byte of a frame has arrived, the rest must finish within this long or the session closes. */
internal const val CAST_FRAME_DEADLINE_MS = 5_000L

internal sealed interface CastSocketOpen {
    /** [pin] is [CastPins.Verdict.PINNED] or [CastPins.Verdict.MATCHED]. */
    data class Opened(val socket: SSLSocket, val pin: CastPins.Verdict) : CastSocketOpen
    /** The device proved an identity key other than the one pinned for its id. */
    data object Untrusted : CastSocketOpen
    /** The deviceauth reply was an AuthError, malformed, or its signature or nonce did not check out. */
    data object AuthFailed : CastSocketOpen
    /** No deviceauth reply within [DEVICE_AUTH_TIMEOUT_MS], or the TV closed the socket first. */
    data object AuthNone : CastSocketOpen
    data object Failed : CastSocketOpen
}

/**
 * TLS to a discovered LAN host on the Cast port, then device authentication before anything else is sent. TLS accepts
 * any non-empty chain for this socket only: the receiver's TLS leaf is self-signed and regenerated every day or two. The
 * receiver then signs a fresh nonce || that leaf's DER with its device key; the device certificate's public key is
 * pinned for [deviceId] on first use and must match on later connections (see [CastPins]). [bind] attaches the socket
 * to Wi-Fi before connect. Fails for any address that is not a private LAN host.
 */
internal fun openCastSocket(address: InetAddress, deviceId: String, pins: CastPins, bind: (Socket) -> Unit): CastSocketOpen {
    if (!isLocalLanAddress(address)) return CastSocketOpen.Failed
    var socket: SSLSocket? = null
    var outcome: CastSocketOpen = CastSocketOpen.Failed
    try {
        val random = SecureRandom()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(LanCastTrust()), random)
        val opening = context.socketFactory.createSocket() as SSLSocket
        socket = opening
        bind(opening)
        opening.connect(InetSocketAddress(address, CAST_PORT), 4_000)
        val protocols = opening.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (protocols.isNotEmpty()) opening.enabledProtocols = protocols.toTypedArray()
        opening.soTimeout = 4_000
        opening.keepAlive = true
        opening.startHandshake()
        val tlsLeafDer = opening.session.peerCertificates.first().encoded
        opening.soTimeout = 400
        val nonce = ByteArray(DEVICE_AUTH_NONCE_BYTES).also(random::nextBytes)
        opening.outputStream.write(frameCastMessage(deviceAuthChallengeMessage(nonce)))
        opening.outputStream.flush()
        val reply = awaitDeviceAuthReply(opening.inputStream)
        val identityKey = reply?.let { verifyDeviceAuth(it, nonce, tlsLeafDer) }
        outcome =
            when {
                reply == null -> CastSocketOpen.AuthNone
                identityKey == null -> CastSocketOpen.AuthFailed
                else ->
                    when (val verdict = pins.check(deviceId, identityKey)) {
                        CastPins.Verdict.CHANGED -> CastSocketOpen.Untrusted
                        else -> CastSocketOpen.Opened(opening, verdict)
                    }
            }
    } catch (_: Exception) {
        outcome = CastSocketOpen.Failed
    }
    if (outcome !is CastSocketOpen.Opened) runCatching { socket?.close() }
    return outcome
}

/**
 * One CASTV2 frame from [input]. A timeout before the first byte is [Read.Tick], so the session thread can run the
 * silence check. After the header is in, the body is read to completion. A timeout on that body closes the session
 * and is never a tick, including a timeout that arrives before the first body byte.
 */
internal fun readCastFrame(
    input: InputStream,
    nowMs: () -> Long = { System.currentTimeMillis() },
): Read {
    return try {
        when (val header = readExact(input, 4, nowMs, idleWhenEmpty = true, deadlineStart = null)) {
            ReadChunk.Idle -> Read.Tick
            ReadChunk.Closed -> Read.Closed
            is ReadChunk.Bytes -> {
                val length = castDeclaredLength(header.data)
                if (length < 0) return Read.Closed
                val started = nowMs()
                when (val body = readExact(input, length, nowMs, idleWhenEmpty = false, deadlineStart = started)) {
                    ReadChunk.Idle, ReadChunk.Closed -> Read.Closed
                    is ReadChunk.Bytes -> decodeCastMessage(body.data)?.let { Read.Frame(it) } ?: Read.Closed
                }
            }
        }
    } catch (_: Exception) {
        Read.Closed
    }
}

private sealed interface ReadChunk {
    data class Bytes(val data: ByteArray) : ReadChunk
    data object Idle : ReadChunk
    data object Closed : ReadChunk
}

private fun readExact(
    input: InputStream,
    size: Int,
    nowMs: () -> Long,
    idleWhenEmpty: Boolean,
    deadlineStart: Long?,
): ReadChunk {
    if (size == 0) return ReadChunk.Bytes(ByteArray(0))
    val buffer = ByteArray(size)
    var offset = 0
    var started = deadlineStart
    while (offset < size) {
        val origin = started
        if (origin != null && nowMs() - origin >= CAST_FRAME_DEADLINE_MS) return ReadChunk.Closed
        val read =
            try {
                input.read(buffer, offset, size - offset)
            } catch (_: SocketTimeoutException) {
                if (offset == 0 && started == null && idleWhenEmpty) return ReadChunk.Idle
                val origin = started ?: nowMs().also { started = it }
                if (nowMs() - origin >= CAST_FRAME_DEADLINE_MS) return ReadChunk.Closed
                continue
            } catch (_: Exception) {
                return ReadChunk.Closed
            }
        if (read < 0) return ReadChunk.Closed
        if (read == 0) continue
        if (started == null) started = nowMs()
        offset += read
    }
    return ReadChunk.Bytes(buffer)
}

private fun dialHost(address: InetAddress): String? {
    if (!isLocalLanAddress(address)) return null
    val raw = address.hostAddress ?: return null
    return if (raw.contains(':')) "[${raw.substringBefore('%')}]" else raw
}

/**
 * Accepts any non-empty chain for one Cast socket; the TV is authenticated afterwards by deviceauth. Not installed on
 * the default SSL context.
 */
private class LanCastTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Cast does not authenticate this phone to the TV")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Cast device presented no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
