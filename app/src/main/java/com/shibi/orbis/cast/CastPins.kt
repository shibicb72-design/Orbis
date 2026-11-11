package com.shibi.orbis.cast

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

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
 * Trust on first use for a Chromecast's identity key. The first device-certificate public key (SPKI DER) that a device
 * id proves it holds through deviceauth (see CastDeviceAuth.kt) is remembered as a SHA-256 fingerprint; later
 * connections must prove the same key. The TLS leaf is not pinned: receivers regenerate it every day or two. The store
 * sees a hash of the device id, not the id.
 */
internal class CastPins(private val store: CastPinStore) {
    enum class Verdict {
        /** No pin existed for this device. The key is now pinned. */
        PINNED,
        MATCHED,
        /** The device proved a different key from the pinned one. The pin is kept. */
        CHANGED,
    }

    @Synchronized
    fun check(deviceId: String, identityKeySpki: ByteArray): Verdict {
        val key = keyFor(deviceId)
        val presented = sha256Hex(identityKeySpki)
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

    /** Drops the pin, so the next connection to [deviceId] pins whatever key it proves. */
    @Synchronized
    fun forget(deviceId: String) {
        store.remove(keyFor(deviceId))
    }

    private fun keyFor(deviceId: String): String = sha256Hex(deviceId.toByteArray(Charsets.UTF_8))
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
