# Orbis release keep rules. R8 is on for release builds only (app/build.gradle.kts).
# GeckoView, Mozilla Android Components, AndroidX, Compose, CameraX and ZXing ship their own consumer rules.
# The reader extension talks to the app through WebExtension messaging (JSON), not reflection, so nothing extra is kept.
# Crash reports from a minified build need app/build/outputs/mapping/release/mapping.txt to be readable.
# Orbis ships no Glean (Mozilla telemetry). Nimbus code pulled in by Android Components still references it; those paths
# never run here, so R8 is told the classes are absent on purpose (rules from build/outputs/mapping/release/missing_rules.txt).
-dontwarn mozilla.telemetry.glean.**

# The Cast SDK instantiates this from the manifest meta-data string, which R8 would otherwise rename.
-keep class com.shibi.orbis.cast.OrbisCastOptionsProvider { <init>(); }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
