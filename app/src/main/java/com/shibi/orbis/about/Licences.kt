package com.shibi.orbis.about

/** A licence text shipped in assets/licenses/, verbatim from upstream. */
enum class LicenceKind(val assetName: String) {
    BSD_3_CLAUSE("bsd-3-libtorrent.txt"),
    MIT("mit-libtorrent4j.txt"),
    BSL_1_0("bsl-1.0.txt"),
    APACHE_2_0("apache-2.0.txt"),
    MPL_2_0("mpl-2.0.txt"),
    ;

    val assetPath: String get() = "licenses/$assetName"
}

/** What a component does in Orbis, shown under its name. */
enum class LicenceNote { TORRENT_ENGINE, INSIDE_LIBTORRENT4J, BROWSER_ENGINE, READER_VIEW, APP_FRAMEWORK, QR_SCANNER }

/** One open-source component the app ships. [version] is null where many libraries of one family ship together. */
class LicenceEntry(val name: String, val version: String?, val licence: LicenceKind, val note: LicenceNote? = null)

/**
 * Everything the app ships with its licence. Versions follow gradle/libs.versions.toml (a test checks that); the
 * libtorrent, Boost and OpenSSL versions are the ones built into libtorrent4j 2.1.0-39 (its release notes and
 * swig/android-build/Dockerfile: libtorrent master at 2.1.0, Boost 1.90.0, OpenSSL 3.5.5).
 */
object Licences {
    const val LIBTORRENT4J_VERSION = "2.1.0-39"
    const val ANDROID_COMPONENTS_VERSION = "159.0.1"
    const val ZXING_VERSION = "3.5.4"

    val entries: List<LicenceEntry> =
        listOf(
            LicenceEntry("libtorrent", "2.1.0", LicenceKind.BSD_3_CLAUSE, LicenceNote.INSIDE_LIBTORRENT4J),
            LicenceEntry("libtorrent4j", LIBTORRENT4J_VERSION, LicenceKind.MIT, LicenceNote.TORRENT_ENGINE),
            LicenceEntry("Boost", "1.90.0", LicenceKind.BSL_1_0, LicenceNote.INSIDE_LIBTORRENT4J),
            LicenceEntry("OpenSSL", "3.5.5", LicenceKind.APACHE_2_0, LicenceNote.INSIDE_LIBTORRENT4J),
            LicenceEntry("GeckoView / Android Components", ANDROID_COMPONENTS_VERSION, LicenceKind.MPL_2_0, LicenceNote.BROWSER_ENGINE),
            LicenceEntry("Readability", null, LicenceKind.APACHE_2_0, LicenceNote.READER_VIEW),
            LicenceEntry("AndroidX / Kotlin", null, LicenceKind.APACHE_2_0, LicenceNote.APP_FRAMEWORK),
            LicenceEntry("ZXing", ZXING_VERSION, LicenceKind.APACHE_2_0, LicenceNote.QR_SCANNER),
        )
}
