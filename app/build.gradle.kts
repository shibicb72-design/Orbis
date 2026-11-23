import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key lives OUTSIDE the repository (never committed): a properties file with storeFile, storePassword,
// keyAlias and keyPassword. Looked for at ORBIS_SIGNING_PROPERTIES, then the owner's key folder
// (~/AndroidBrowser_work/ORBIS_UPLOAD_KEY_DO_NOT_DELETE/orbis-upload.properties). When its storeFile path does not exist
// on this machine, the keystore next to the properties file is used. Without it (CI, other machines) the release build
// is simply unsigned.
val uploadSigningFile: File? =
    listOfNotNull(
        System.getenv("ORBIS_SIGNING_PROPERTIES"),
        "${System.getProperty("user.home")}/AndroidBrowser_work/ORBIS_UPLOAD_KEY_DO_NOT_DELETE/orbis-upload.properties",
    ).map { file(it) }.firstOrNull { it.isFile }
val uploadSigning: Properties? =
    uploadSigningFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val uploadKeystore: File? =
    uploadSigning?.getProperty("storeFile")?.let { path ->
        file(path).takeIf { it.isFile } ?: File(uploadSigningFile!!.parentFile, File(path).name).takeIf { it.isFile }
    }

android {
    namespace = "com.shibi.orbis"
    // GeckoView/AC 160 AAR metadata requires Android API 37.2+ (platform package platforms;android-37.2).
    // House targetSdk stays 36; minSdk is 31 (Android 12, owner decision 2026-11-11).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.shibi.orbis"
        minSdk = 31
        targetSdk = 36
        versionCode = 30
        versionName = "0.8.3"
    }

    splits {
        abi {
            // Per-ABI APKs for direct installs. An app bundle (Play) splits by ABI itself and cannot be built with
            // these on, so they are off when a bundle task is requested.
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // Debug-only, NOT secret, shared house keystore. Never used for Play release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadSigning != null && uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = uploadSigning.getProperty("storePassword")
                keyAlias = uploadSigning.getProperty("keyAlias")
                keyPassword = uploadSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Own application id: a debug install (public debug key) sits beside the Play app with its own data.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        // Own application id, so destructive browser-data tests get their own app data and never touch com.shibi.orbis.
        create("fixture") {
            initWith(getByName("debug"))
            // Replaces the inherited ".debug": the id is com.shibi.orbis.fixture, not com.shibi.orbis.debug.fixture.
            applicationIdSuffix = ".fixture"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            // R8 shrinking and obfuscation for anything built for release; GeckoView, AC and AndroidX ship their own
            // consumer keep rules. Debug and fixture builds stay unminified.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the Play upload key when its properties file exists on this machine (see uploadSigning).
            if (uploadSigning != null && uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
        // The release build (R8 shrinking included) under its own id, debug-signed: installs beside the others on a test
        // device or emulator to check that shrinking broke nothing at run time. Never shipped.
        create("r8check") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8check"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the torrent debug intent (adb verification only).
        buildConfig = true
    }

    testOptions {
        // Robolectric needs the merged manifest/resources for the JVM Compose tests (test classpath only).
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Torrent natives: src/main/jniLibs/<abi>/libtorrent4j.so are the unmodified artifacts of one Torrent natives workflow
// run on main (native/libtorrent4j/README.md). Before every build, each file under src/main/jniLibs is hashed and
// compared with native/libtorrent4j/CHECKSUMS.sha256; a changed byte, a missing file or an extra file fails the build.
// A jniLibs directory in any other source set (src/debug, src/release, src/<flavour>, ...) fails it too: AGP would
// package it next to, or instead of, the checked files.
val verifyTorrentNatives = tasks.register("verifyTorrentNatives") {
    group = "verification"
    description = "Checks src/main/jniLibs against native/libtorrent4j/CHECKSUMS.sha256; no other src/*/jniLibs."
    val repoRoot = rootProject.layout.projectDirectory.asFile
    val srcDir = layout.projectDirectory.dir("src").asFile
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val checksums = rootProject.layout.projectDirectory.file("native/libtorrent4j/CHECKSUMS.sha256").asFile
    inputs.file(checksums)
    inputs.files(jniLibs)
    doLast {
        val otherJniLibs = srcDir.listFiles().orEmpty().filter { it.isDirectory && it.name != "main" }
            .map { File(it, "jniLibs") }.filter { it.exists() }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }.sorted()
        if (otherJniLibs.isNotEmpty()) {
            throw GradleException(
                "Native libraries belong in app/src/main/jniLibs only (native/libtorrent4j/README.md). Remove: " +
                    otherJniLibs.joinToString(", "),
            )
        }
        val hexSha256 = Regex("[0-9a-f]{64}")
        val expected = mutableMapOf<String, String>()
        checksums.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2 || !hexSha256.matches(parts[0])) throw GradleException("Bad line in $checksums: $line")
            val path = parts[1].removePrefix("*")
            if (expected.put(path, parts[0]) != null) throw GradleException("Duplicate path in $checksums: $path")
        }
        val actual = jniLibs.walkTopDown().filter { it.isFile }.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            file.relativeTo(repoRoot).invariantSeparatorsPath to digest.digest().joinToString("") { "%02x".format(it) }
        }
        val problems = buildList {
            (expected.keys - actual.keys).sorted().forEach { add("missing: $it") }
            (actual.keys - expected.keys).sorted().forEach { add("not in CHECKSUMS.sha256: $it") }
            expected.keys.intersect(actual.keys).sorted().filter { expected[it] != actual[it] }
                .forEach { add("SHA-256 mismatch: $it is ${actual[it]}, expected ${expected[it]}") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Torrent natives do not match native/libtorrent4j/CHECKSUMS.sha256:\n" + problems.joinToString("\n"),
            )
        }
        logger.lifecycle("Torrent natives: ${actual.size} files match CHECKSUMS.sha256")
    }
}
tasks.named("preBuild") { dependsOn(verifyTorrentNatives) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Unit tests (test scope only): Robolectric's Android runtime jars come through Gradle, not from Robolectric's own
// downloader. Gradle fetches exactly the jars below (cached, checked against gradle/verification-metadata.xml), the
// copy task puts them in build/robolectric-android-all, and every Test task runs Robolectric in offline mode from that
// folder (robolectric.offline and robolectric.dependency.dir, read by Robolectric 4.17 LegacyDependencyResolver), so
// the test JVMs need no network. The SDK levels are the ones the tests ask for with @Config(sdk = ...): 31, 33, 35.
// A test that asks for another level fails with the message from the listener below; to add a level, add its
// org.robolectric:android-all-instrumented jar here, in gradle/libs.versions.toml and in gradle/verification-metadata.xml.
// One configuration per level: all three are the same module in different versions, and one configuration would keep only the newest.
val robolectricAndroidAll = listOf(
    31 to libs.versions.robolectricAndroidAllSdk31,
    33 to libs.versions.robolectricAndroidAllSdk33,
    35 to libs.versions.robolectricAndroidAllSdk35,
).map { (sdk, jar) ->
    configurations.create("robolectricAndroidAllSdk$sdk") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        dependencies.addLater(jar.map { project.dependencies.create("org.robolectric:android-all-instrumented:$it") })
    }
}
val robolectricAndroidAllDir = layout.buildDirectory.dir("robolectric-android-all")
val copyRobolectricAndroidAll = tasks.register<Sync>("copyRobolectricAndroidAll") {
    from(robolectricAndroidAll)
    into(robolectricAndroidAllDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricAndroidAll)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricAndroidAllDir.get().asFile.absolutePath)
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            val missing = result.exceptions.any { error ->
                generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().let { m -> m.contains("Path is not a file") && m.contains("robolectric-android-all") } }
            }
            if (missing) {
                logger.error(
                    "${testDescriptor.className}: Robolectric needs an Android jar that is not in app/build.gradle.kts " +
                        "(configurations robolectricAndroidAllSdk<level>). Tests run offline and never download it. Add the SDK level " +
                        "there, in gradle/libs.versions.toml and in gradle/verification-metadata.xml."
                )
            }
        }
    })
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Path B: matching GeckoView + AC pair (see libs.versions.toml)
    implementation(libs.mozilla.geckoview)
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.concept.engine)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.browser)
    // Torrents: only torrent/Libtorrent4jEngine.kt may touch it; the native library loads only while a torrent is active.
    // The Java API comes from Maven Central; libtorrent4j.so is ours (src/main/jniLibs, see verifyTorrentNatives).
    implementation(libs.libtorrent4j.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}