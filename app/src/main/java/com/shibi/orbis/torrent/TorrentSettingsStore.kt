package com.shibi.orbis.torrent

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where torrent settings are kept: one key per field. An absent key reads as null (the owner's default then). */
interface TorrentPrefs {
    fun boolean(key: String): Boolean?

    fun int(key: String): Int?

    fun float(key: String): Float?

    /** Writes every pair at once; a null value removes the key. */
    fun write(values: Map<String, Any?>)
}

class SharedPreferencesTorrentPrefs(private val prefs: SharedPreferences) : TorrentPrefs {
    // A value of the wrong type (a file from elsewhere) reads as absent, not as a crash.
    override fun boolean(key: String): Boolean? = if (prefs.contains(key)) runCatching { prefs.getBoolean(key, false) }.getOrNull() else null

    override fun int(key: String): Int? = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null

    override fun float(key: String): Float? = if (prefs.contains(key)) runCatching { prefs.getFloat(key, 0f) }.getOrNull() else null

    override fun write(values: Map<String, Any?>) {
        prefs.edit {
            for ((k, v) in values) {
                when (v) {
                    null -> remove(k)
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Float -> putFloat(k, v)
                    else -> error("unsupported ${v.javaClass.simpleName}")
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "torrent_settings"
    }
}

/**
 * Settings > Torrents. App-wide (private tabs included) and never written to the torrent index. Every value is clamped
 * when read and when written, so a hand-edited or older file cannot carry a negative cap or a ratio of 0.
 *
 * Also keeps the engine's [listenPort], which is not a user setting.
 */
class TorrentSettingsStore(private val prefs: TorrentPrefs, private val random: Random = SecureRandom()) {
    constructor(shared: SharedPreferences) : this(SharedPreferencesTorrentPrefs(shared))

    private val _settings =
        MutableStateFlow(
            run {
                migrate()
                read()
            },
        )
    val settings: StateFlow<TorrentSettings> = _settings.asStateFlow()

    /**
     * The port torrents listen on: picked at random in [LISTEN_PORT_MIN]..[LISTEN_PORT_MAX] the first time it is
     * asked for, then the same on every start. A stored value outside the range is replaced by a new one.
     */
    @Synchronized
    fun listenPort(): Int {
        prefs.int(KEY_LISTEN_PORT)?.takeIf { it in LISTEN_PORT_MIN..LISTEN_PORT_MAX }?.let { return it }
        val port = LISTEN_PORT_MIN + random.nextInt(LISTEN_PORT_MAX - LISTEN_PORT_MIN + 1)
        prefs.write(mapOf(KEY_LISTEN_PORT to port))
        return port
    }

    /**
     * Files from before [SCHEMA] stored "Local peers" on (the old default) as soon as any torrent setting changed, so
     * a stored "on" there is the old default, not a choice: it is dropped and the new default (off) applies. A stored
     * "off" was a choice and stays.
     */
    private fun migrate() {
        if ((prefs.int(KEY_SCHEMA) ?: 0) >= SCHEMA) return
        if (prefs.boolean(KEY_LSD) == true) prefs.write(mapOf(KEY_LSD to null, KEY_SCHEMA to SCHEMA))
    }

    /** Applies [block] to the current settings, writes the clamped result and emits it (nothing when unchanged). */
    @Synchronized
    fun update(block: (TorrentSettings) -> TorrentSettings) {
        val before = _settings.value
        val next = block(before).clamped()
        if (next == before) return
        prefs.write(
            mapOf(
                KEY_WIFI_ONLY to next.wifiOnly,
                KEY_STOP_SEEDING to next.stopSeeding,
                KEY_SEED_RATIO to next.seedRatioLimit,
                KEY_DHT to next.dht,
                KEY_PEX to next.pex,
                KEY_LSD to next.lsd,
                KEY_DOWN_BPS to next.downBps,
                KEY_UP_BPS to next.upBps,
                KEY_SCHEMA to SCHEMA,
            ),
        )
        _settings.value = next
    }

    private fun read(): TorrentSettings {
        val d = TorrentSettings()
        return TorrentSettings(
            wifiOnly = prefs.boolean(KEY_WIFI_ONLY) ?: d.wifiOnly,
            stopSeeding = prefs.boolean(KEY_STOP_SEEDING) ?: d.stopSeeding,
            dht = prefs.boolean(KEY_DHT) ?: d.dht,
            pex = prefs.boolean(KEY_PEX) ?: d.pex,
            lsd = prefs.boolean(KEY_LSD) ?: d.lsd,
            downBps = prefs.int(KEY_DOWN_BPS) ?: d.downBps,
            upBps = prefs.int(KEY_UP_BPS) ?: d.upBps,
            seedRatioLimit = prefs.float(KEY_SEED_RATIO),
        ).clamped()
    }

    companion object {
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_STOP_SEEDING = "stop_seeding"
        const val KEY_SEED_RATIO = "seed_ratio"
        const val KEY_DHT = "dht"
        const val KEY_PEX = "pex"
        const val KEY_LSD = "lsd"
        const val KEY_DOWN_BPS = "down_bps"
        const val KEY_UP_BPS = "up_bps"
        const val KEY_LISTEN_PORT = "listen_port"

        /** 2: "Local peers" defaults to off (see [migrate]). Absent: written by an older version. */
        const val KEY_SCHEMA = "schema"
        const val SCHEMA = 2

        /** The dynamic (private) port range: no well-known BitTorrent port, nothing that marks the client. */
        const val LISTEN_PORT_MIN = 49152
        const val LISTEN_PORT_MAX = 65535
    }
}
