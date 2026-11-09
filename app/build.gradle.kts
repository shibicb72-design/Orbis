import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key lives OUTSIDE the repository (never committed): a keystore.properties file with storeFile,
// storePassword, keyAlias and keyPassword, at ORBIS_SIGNING_PROPERTIES or ~/orbis-signing/keystore.properties.
// Without it (CI, other machines) the release build is simply unsigned.
val uploadSigning: Properties? =
    (System.getenv("ORBIS_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/orbis-signing/keystore.properties")
        .let { file(it) }
        .takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.shibi.orbis"
    // GeckoView/AC 159 AAR metadata requires Android API 37.2+ (platform package platforms;android-37.2).
    // House targetSdk stays 36; minSdk stays 29.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.shibi.orbis"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.4.10"
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
        if (uploadSigning != null) {
            create("upload") {
                storeFile = file(uploadSigning.getProperty("storeFile"))
                storePassword = uploadSigning.getProperty("storePassword")
                keyAlias = uploadSigning.getProperty("keyAlias")
                keyPassword = uploadSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // Own application id, so destructive browser-data tests get their own app data and never touch com.shibi.orbis.
        create("fixture") {
            initWith(getByName("debug"))
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
            if (uploadSigning != null) signingConfig = signingConfigs.getByName("upload")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}