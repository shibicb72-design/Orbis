package com.shibi.orbis.ui.settings

import com.shibi.orbis.torrent.TorrentSettings

/** What a speed limit row says. */
sealed interface LimitLabel {
    data object NoLimit : LimitLabel

    data class Kbps(val kbps: Int) : LimitLabel
}

/** The row texts of Settings > Torrents, from the settings. Pure, so the screen's wording rules are unit tested. */
data class TorrentSettingsRowModel(
    val down: LimitLabel,
    val up: LimitLabel,
    /** "Stop at ratio" only exists while "Stop seeding when finished" is off. */
    val ratioRowVisible: Boolean,
    /** "1.5", or null for no limit. */
    val ratio: String?,
) {
    companion object {
        fun from(s: TorrentSettings): TorrentSettingsRowModel =
            TorrentSettingsRowModel(
                down = limit(s.downBps),
                up = limit(s.upBps),
                ratioRowVisible = !s.stopSeeding,
                ratio = s.seedRatioLimit?.let { formatRatio(it) },
            )

        private fun limit(bps: Int): LimitLabel {
            val kbps = TorrentSettings.bpsToKbps(bps)
            return if (bps <= 0) LimitLabel.NoLimit else LimitLabel.Kbps(kbps.coerceAtLeast(1))
        }

        /** One decimal, a dot whatever the language (the field takes a dot or a comma). */
        fun formatRatio(ratio: Float): String = String.format(java.util.Locale.ROOT, "%.1f", ratio)

        /** The KB/s field: a whole number in 0..1,000,000 (empty is 0, no limit); null when it is not one. */
        fun parseKbps(text: String): Int? {
            val t = text.trim()
            if (t.isEmpty()) return 0
            if (t.length > 7 || !t.all { it in '0'..'9' }) return null
            return t.toInt().takeIf { it in 0..TorrentSettings.MAX_KBPS }
        }

        /** The ratio field: 0.1..100 with at most one decimal; null when it is not one. */
        fun parseRatio(text: String): Float? {
            val t = text.trim().replace(',', '.')
            if (!Regex("""\d{1,3}(\.\d)?""").matches(t)) return null
            val v = t.toFloatOrNull() ?: return null
            return v.takeIf { it >= TorrentSettings.MIN_RATIO && it <= TorrentSettings.MAX_RATIO }
        }
    }
}
