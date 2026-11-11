package com.shibi.orbis.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shibi.orbis.youtube.HttpsLoungeTransport
import com.shibi.orbis.youtube.LoungeClient
import java.io.InputStream
import java.io.OutputStream
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
 * host. It is not installed as the process trust manager. The TV is identified by its deviceauth key, which the user
 * confirms once per key and name before anything is sent to it (see [authenticateCastPeer] and [CastPins]).
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
        /** Gets the reason, the ticket, and the friendly name that was checked (as read when connecting). */
        onNeedsTrust: (CastPins.Reason, CastPins.Ticket, String) -> Unit,
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
            val opened = openCastSocket(device.address, device.id, device.name, pins) { LanBind.bind(appContext, it) }
            val connectMs = System.currentTimeMillis() - connectStart
            when (opened) {
                is CastSocketOpen.NeedsTrust -> log("connect dev=$dev trust needed reason=${opened.reason.name}")
                CastSocketOpen.AuthFailed -> log("connect dev=$dev deviceauth failed")
                CastSocketOpen.AuthNone -> log("connect dev=$dev deviceauth none")
                CastSocketOpen.Failed -> log("connect dev=$dev failed lan=${isLocalLanAddress(device.address)} ms=$connectMs")
                is CastSocketOpen.Opened ->
                    log(
                        "connect dev=$dev tls=ok deviceauth=ok${if (opened.legacy) " legacy" else ""} " +
                            "pin=${if (opened.pin == CastPins.Verdict.PINNED) "pinned" else "matched"} ms=$connectMs",
                    )
            }
            val socket = (opened as? CastSocketOpen.Opened)?.socket
            if (socket == null || !isCurrent(epoch)) {
                runCatching { socket?.close() }
                if (isCurrent(epoch)) {
                    if (opened is CastSocketOpen.NeedsTrust) {
                        main.post { onNeedsTrust(opened.reason, opened.ticket, device.name) }
                    } else {
                        main.post { onFailed(CastFailure.GENERIC) }
                    }
                }
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
                val crash = { if (isCurrent(epoch) && !session.localStop && !ready) main.post { onFailed(CastFailure.GENERIC) } }
                cause = runGuardedCastSession(onCrash = crash) {
                    var loopCause = "localStop"
                    apply(session, machine.start(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                    while (isCurrent(epoch) && !session.localStop) {
                        drain(session)
                        if (!isCurrent(epoch) || session.localStop) break
                        when (val read = readCastFrame(socket.inputStream)) {
                            Read.Tick -> apply(session, machine.onClock(System.currentTimeMillis()), onReady, onFailed) { ready = true }
                            Read.Closed -> {
                                loopCause = "closed"
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
                    if (loopCause == "localStop" && !session.localStop) loopCause = "replaced"
                    loopCause
                }
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

    /** The user tapped "Trust and cast". The next connection pins [ticket]'s key and name if the device proves them again. */
    fun approveTrust(ticket: CastPins.Ticket) = pins.approve(ticket, System.currentTimeMillis())

    fun cancelTrust() = pins.cancelApproval()

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
    /** [pin] is [CastPins.Verdict.PINNED] or [CastPins.Verdict.MATCHED]. [legacy]: see [DeviceIdentity.legacy]. */
    data class Opened(val socket: SSLSocket, val pin: CastPins.Verdict, val legacy: Boolean) : CastSocketOpen
    /** The device proved its key, but this key and name are not trusted yet. The socket is closed; ask the user. */
    data class NeedsTrust(val reason: CastPins.Reason, val ticket: CastPins.Ticket) : CastSocketOpen
    /** The deviceauth reply was an AuthError, malformed, or its signature or nonce did not check out. */
    data object AuthFailed : CastSocketOpen
    /** No deviceauth reply within [DEVICE_AUTH_TIMEOUT_MS], or the TV closed the socket first. */
    data object AuthNone : CastSocketOpen
    data object Failed : CastSocketOpen
}

/**
 * TLS to a discovered LAN host on the Cast port, then device authentication before anything else is sent. TLS accepts
 * any non-empty chain for this socket only: the receiver's TLS leaf is self-signed and regenerated every day or two. The
 * receiver then signs a fresh nonce || that leaf's DER with its device key; see [authenticateCastPeer] for what is
 * trusted. [bind] attaches the socket to Wi-Fi before connect. Fails for any address that is not a private LAN host.
 * Only [CastSocketOpen.Opened] leaves the socket open.
 */
internal fun openCastSocket(
    address: InetAddress,
    deviceId: String,
    name: String,
    pins: CastPins,
    bind: (Socket) -> Unit,
): CastSocketOpen {
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
        outcome =
            when (val peer = authenticateCastPeer(opening.inputStream, opening.outputStream, tlsLeafDer, nonce, deviceId, name, pins)) {
                is CastPeerAuth.Trusted -> CastSocketOpen.Opened(opening, peer.pin, peer.legacy)
                is CastPeerAuth.NeedsTrust -> CastSocketOpen.NeedsTrust(peer.reason, peer.ticket)
                CastPeerAuth.AuthFailed -> CastSocketOpen.AuthFailed
                CastPeerAuth.AuthNone -> CastSocketOpen.AuthNone
            }
    } catch (_: Exception) {
        outcome = CastSocketOpen.Failed
    }
    if (outcome !is CastSocketOpen.Opened) runCatching { socket?.close() }
    return outcome
}

internal sealed interface CastPeerAuth {
    /** [pin] is [CastPins.Verdict.MATCHED] or [CastPins.Verdict.PINNED]. */
    data class Trusted(val pin: CastPins.Verdict, val legacy: Boolean) : CastPeerAuth
    data class NeedsTrust(val reason: CastPins.Reason, val ticket: CastPins.Ticket) : CastPeerAuth
    data object AuthFailed : CastPeerAuth
    data object AuthNone : CastPeerAuth
}

/**
 * The part of [openCastSocket] after the TLS handshake. Writes exactly one frame, the deviceauth challenge for
 * [nonce], and reads the reply. A device that proves a key is then checked against [pins] under [name]: only
 * [CastPeerAuth.Trusted] lets the caller send anything else, so no media request reaches a device the user has not
 * confirmed. The signature covers the nonce and [tlsLeafDer], so a key proved here cannot be relayed by a middle device.
 */
internal fun authenticateCastPeer(
    input: InputStream,
    output: OutputStream,
    tlsLeafDer: ByteArray,
    nonce: ByteArray,
    deviceId: String,
    name: String,
    pins: CastPins,
    nowMs: () -> Long = { System.currentTimeMillis() },
): CastPeerAuth {
    output.write(frameCastMessage(deviceAuthChallengeMessage(nonce)))
    output.flush()
    val reply = awaitDeviceAuthReply(input, nowMs) ?: return CastPeerAuth.AuthNone
    val identity = verifyDeviceAuth(reply, nonce, tlsLeafDer) ?: return CastPeerAuth.AuthFailed
    return when (val verdict = pins.check(deviceId, name, identity.spki, nowMs())) {
        is CastPins.Verdict.NeedsConfirm -> CastPeerAuth.NeedsTrust(verdict.reason, verdict.ticket)
        else -> CastPeerAuth.Trusted(verdict, identity.legacy)
    }
}

/**
 * Runs one session's read loop and returns how it ended. An exception or a [StackOverflowError] (a payload nested
 * deeper than the JSON scan caught) ends the session: [onCrash] runs and the cause names the error class. Other errors,
 * such as [OutOfMemoryError], are not caught.
 */
internal fun runGuardedCastSession(onCrash: () -> Unit, loop: () -> String): String =
    try {
        loop()
    } catch (e: Exception) {
        onCrash()
        "error err=${errName(e)}"
    } catch (_: StackOverflowError) {
        onCrash()
        "error err=StackOverflowError"
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
