# Orbis release keep rules. R8 is on for release builds only (app/build.gradle.kts).
# GeckoView, Mozilla Android Components, AndroidX, Compose, CameraX and ZXing ship their own consumer rules.
# The reader extension talks to the app through WebExtension messaging (JSON), not reflection, so nothing extra is kept.
# Crash reports from a minified build need app/build/outputs/mapping/release/mapping.txt to be readable.
# Orbis ships no Glean (Mozilla telemetry). Nimbus code pulled in by Android Components still references it; those paths
# never run here, so R8 is told the classes are absent on purpose (rules from build/outputs/mapping/release/missing_rules.txt).
-dontwarn mozilla.telemetry.glean.**

# libtorrent4j: the native library calls back into the SWIG classes by name (JNI), so R8 must keep them whole.
# Rules from the libtorrent4j README (https://github.com/aldenml/libtorrent4j).
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }

# The engine's start-up prefs file (brief priv11-3-connectivity-probe-off.md) is read by GeckoView's DebugConfig with SnakeYAML,
# whose static initializers call getPackage().getName(): renamed into the root package, getPackage() is null and engine start
# crashes. Mozilla's own fix (Bug 2089508) adds this rule to GeckoView's consumer rules; remove it here once the engine's rules carry it.
-keepnames class org.yaml.snakeyaml.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds write no page address to the system log (brief priv11-1-engine-log-addresses.md).
# GeckoView's GeckoSession logs "handleMessage ... uri=<address>" with a plain android.util.Log.d (not behind its DEBUG flag),
# which put every page address, private tabs included, into logcat. R8 removes Log.v and Log.d calls from the release build;
# Log.i, Log.w and Log.e are untouched.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
