package com.shibi.orbis.cast

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Where [CastPins] keeps one fingerprint per device. Keys and values are hex strings. */
internal interface CastPinStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/**
 * The private `cast_pins` preferences file. Clear browsing data does not touch it: a pin protects the user, and
 * dropping it would let the next impostor be trusted as a first connection.
 */
internal class PrefsCastPinStore(context: Context) : CastPinStore {
    private val prefs = context.applicationContext.getSharedPreferences(CAST_PINS_FILE, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }
}

internal const val CAST_PINS_FILE = "cast_pins"

/**
 * Trust on first use for Chromecast TLS. The first leaf certificate a device id presents is remembered as a SHA-256
 * fingerprint; later connections must present the same certificate. The store sees a hash of the device id, not the id.
 */
internal class CastPins(private val store: CastPinStore) {
    enum class Verdict {
        /** No pin existed for this device. The certificate is now pinned. */
        PINNED,
        MATCHED,
        /** The device presented a different certificate from the pinned one. The pin is kept. */
        CHANGED,
    }

    @Synchronized
    fun check(deviceId: String, leafEncoded: ByteArray): Verdict {
        val key = keyFor(deviceId)
        val presented = sha256Hex(leafEncoded)
        val pinned = store.get(key)
        return when {
            pinned == null -> {
                store.put(key, presented)
                Verdict.PINNED
            }
            MessageDigest.isEqual(pinned.toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII)) ->
                Verdict.MATCHED
            else -> Verdict.CHANGED
        }
    }

    /** Drops the pin, so the next connection to [deviceId] pins whatever certificate it presents. */
    @Synchronized
    fun forget(deviceId: String) {
        store.remove(keyFor(deviceId))
    }

    private fun keyFor(deviceId: String): String = sha256Hex(deviceId.toByteArray(Charsets.UTF_8))
}

/** Thrown during the handshake when a device's certificate no longer matches its pin. */
internal class CastPinMismatchException : CertificateException("Cast device certificate changed")

/**
 * Trusts the peer certificate of one Cast socket when it matches the pin for [deviceId], or when there is no pin yet.
 * Not installed on the default SSL context. [changed] tells the caller that a failed handshake was a pin mismatch.
 */
internal class PinnedCastTrust(private val deviceId: String, private val pins: CastPins) : X509TrustManager {
    @Volatile
    var changed: Boolean = false
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Cast does not authenticate this phone to the TV")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Cast device presented no certificate")
        if (pins.check(deviceId, leaf.encoded) == CastPins.Verdict.CHANGED) {
            changed = true
            throw CastPinMismatchException()
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
