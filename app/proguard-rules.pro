# Orbis release keep rules. R8 is on for release builds only (app/build.gradle.kts).
# GeckoView, Mozilla Android Components, AndroidX, Compose, CameraX and ZXing ship their own consumer rules.
# The reader extension talks to the app through WebExtension messaging (JSON), not reflection, so nothing extra is kept.
# Crash reports from a minified build need app/build/outputs/mapping/release/mapping.txt to be readable.
# Orbis ships no Glean (Mozilla telemetry). Nimbus code pulled in by Android Components still references it; those paths
# never run here, so R8 is told the classes are absent on purpose (rules from build/outputs/mapping/release/missing_rules.txt).
-dontwarn mozilla.telemetry.glean.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# retireGithubUpdateChecker cancels a leftover job by reflection. Android Components ship WorkManager;
# keep the methods that call uses so R8 does not remove them.
-keepclassmembers class androidx.work.WorkManager {
    public static androidx.work.WorkManager getInstance(android.content.Context);
    public androidx.work.Operation cancelUniqueWork(java.lang.String);
    public androidx.work.Operation cancelAllWorkByTag(java.lang.String);
}
