package com.shibi.orbis.about

/**
 * A licence text shipped in assets/licenses/, verbatim from upstream. Components under the same licence whose
 * upstream file carries its own copyright line (BSD, MIT) or that publish their own copy get their own text.
 */
enum class LicenceKind(val assetName: String, val family: LicenceFamily) {
    BSD_3_CLAUSE("bsd-3-libtorrent.txt", LicenceFamily.BSD_3_CLAUSE),
    MIT("mit-libtorrent4j.txt", LicenceFamily.MIT),
    BSL_1_0("bsl-1.0.txt", LicenceFamily.BSL_1_0),
    APACHE_2_0("apache-2.0.txt", LicenceFamily.APACHE_2_0),
    MPL_2_0("mpl-2.0.txt", LicenceFamily.MPL_2_0),

    // The libraries libtorrent's WebTorrent support compiles into libtorrent4j.so: each file is the LICENSE of the
    // submodule commit recorded in native/libtorrent4j/BUILDINFO-<abi>.json (a test checks the commits).
    MPL_2_0_LIBDATACHANNEL("mpl-2.0-libdatachannel.txt", LicenceFamily.MPL_2_0),
    MPL_2_0_LIBJUICE("mpl-2.0-libjuice.txt", LicenceFamily.MPL_2_0),
    BSD_3_CLAUSE_USRSCTP("bsd-3-usrsctp.txt", LicenceFamily.BSD_3_CLAUSE),
    MIT_PLOG("mit-plog.txt", LicenceFamily.MIT),
    BSD_3_CLAUSE_TRY_SIGNAL("bsd-3-try-signal.txt", LicenceFamily.BSD_3_CLAUSE),
    ;

    val assetPath: String get() = "licenses/$assetName"
}

/** The licence's name as shown on the card. */
enum class LicenceFamily { BSD_3_CLAUSE, MIT, BSL_1_0, APACHE_2_0, MPL_2_0 }

/** What a component does in Orbis, shown under its name. */
enum class LicenceNote { TORRENT_ENGINE, INSIDE_LIBTORRENT4J, BROWSER_ENGINE, READER_VIEW, APP_FRAMEWORK, QR_SCANNER }

/**
 * One open-source component the app ships. [version] is null where many libraries of one family ship together.
 * [source] is where its source code is, at the commit we ship (MPL-2.0 section 3.2 requires telling users).
 */
class LicenceEntry(
    val name: String,
    val version: String?,
    val licence: LicenceKind,
    val note: LicenceNote? = null,
    val source: String? = null,
)

/**
 * Everything the app ships with its licence. Versions follow gradle/libs.versions.toml (a test checks that); the
 * libtorrent, Boost and OpenSSL versions are the ones in our libtorrent4j.so (native/libtorrent4j/build.env and
 * versions.env: libtorrent 2.1.0 at commit 24a3adf, Boost 1.90.0, OpenSSL [OPENSSL_VERSION]; a test checks OpenSSL).
 * libdatachannel, libjuice, usrsctp, plog and try_signal are libtorrent submodules at the commits in
 * native/libtorrent4j/BUILDINFO-<abi>.json; each version is the one its build files declare at that commit.
 */
object Licences {
    const val LIBTORRENT4J_VERSION = "2.1.0-39"
    const val OPENSSL_VERSION = "3.5.9"
    const val ANDROID_COMPONENTS_VERSION = "160.0"
    const val ZXING_VERSION = "3.5.4"

    const val LIBDATACHANNEL_COMMIT = "a4d3211da33bc1114ae50d2dd12f4186b67aaba4"
    const val LIBJUICE_COMMIT = "5948a4162d37bc213d6051b67ee2876ccc5a99a6"
    const val USRSCTP_COMMIT = "fec583d54493f879d2ae44a743423bf8a04371ab"
    const val PLOG_COMMIT = "94899e0b926ac1b0f4750bfbd495167b4a6ae9ef"
    const val TRY_SIGNAL_COMMIT = "105cce59972f925a33aa6b1c3109e4cd3caf583d"

    val entries: List<LicenceEntry> =
        listOf(
            LicenceEntry("libtorrent", "2.1.0", LicenceKind.BSD_3_CLAUSE, LicenceNote.INSIDE_LIBTORRENT4J),
            LicenceEntry("libtorrent4j", LIBTORRENT4J_VERSION, LicenceKind.MIT, LicenceNote.TORRENT_ENGINE),
            LicenceEntry("Boost", "1.90.0", LicenceKind.BSL_1_0, LicenceNote.INSIDE_LIBTORRENT4J),
            LicenceEntry("OpenSSL", OPENSSL_VERSION, LicenceKind.APACHE_2_0, LicenceNote.INSIDE_LIBTORRENT4J),
            // CMakeLists.txt: 0.24.1; the commit is 2 after tag v0.24.1.
            LicenceEntry(
                "libdatachannel",
                "0.24.1 + 2 commits (${LIBDATACHANNEL_COMMIT.take(7)})",
                LicenceKind.MPL_2_0_LIBDATACHANNEL,
                LicenceNote.INSIDE_LIBTORRENT4J,
                "https://github.com/paullouisageneau/libdatachannel/tree/$LIBDATACHANNEL_COMMIT",
            ),
            // Tag v1.7.0.
            LicenceEntry(
                "libjuice",
                "1.7.0",
                LicenceKind.MPL_2_0_LIBJUICE,
                LicenceNote.INSIDE_LIBTORRENT4J,
                "https://github.com/paullouisageneau/libjuice/tree/$LIBJUICE_COMMIT",
            ),
            // libdatachannel's fork of sctplab/usrsctp, untagged; usrsctplib/CMakeLists.txt, configure.ac: 0.9.5.0.
            LicenceEntry(
                "usrsctp",
                "0.9.5.0 (${USRSCTP_COMMIT.take(7)})",
                LicenceKind.BSD_3_CLAUSE_USRSCTP,
                LicenceNote.INSIDE_LIBTORRENT4J,
                "https://github.com/paullouisageneau/usrsctp/tree/$USRSCTP_COMMIT",
            ),
            // CMakeLists.txt: 1.1.10; the commit is 15 after tag 1.1.10 and before 1.1.11.
            LicenceEntry(
                "plog",
                "1.1.10 + 15 commits (${PLOG_COMMIT.take(7)})",
                LicenceKind.MIT_PLOG,
                LicenceNote.INSIDE_LIBTORRENT4J,
                "https://github.com/SergiusTheBest/plog/tree/$PLOG_COMMIT",
            ),
            // No tags and no version in its build files.
            LicenceEntry(
                "try_signal",
                "unknown, untagged (${TRY_SIGNAL_COMMIT.take(7)})",
                LicenceKind.BSD_3_CLAUSE_TRY_SIGNAL,
                LicenceNote.INSIDE_LIBTORRENT4J,
                "https://github.com/arvidn/try_signal/tree/$TRY_SIGNAL_COMMIT",
            ),
            LicenceEntry("GeckoView / Android Components", ANDROID_COMPONENTS_VERSION, LicenceKind.MPL_2_0, LicenceNote.BROWSER_ENGINE),
            LicenceEntry("Readability", null, LicenceKind.APACHE_2_0, LicenceNote.READER_VIEW),
            LicenceEntry("AndroidX / Kotlin", null, LicenceKind.APACHE_2_0, LicenceNote.APP_FRAMEWORK),
            LicenceEntry("ZXing", ZXING_VERSION, LicenceKind.APACHE_2_0, LicenceNote.QR_SCANNER),
        )
}
