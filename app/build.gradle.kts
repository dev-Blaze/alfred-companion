import java.util.Properties

plugins {
    // Kotlin compilation itself is built into AGP 9+ — no org.jetbrains.kotlin.android plugin needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same keystore.properties/keystore/ pair as the phone app (both gitignored) — GMS only delivers
// Data Layer events between nodes whose apps share BOTH applicationId and signing certificate,
// so the watch app must be signed with the phone app's release key. That applies to debug builds
// too: the phone runs a release-signed build, so a debug-signed watch build would silently fail
// to deliver exactly like the original com.yshah.alfred.wear/debug-key build did.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    } else {
        // Without this the build quietly falls back to the debug key and produces an APK that
        // installs, opens, records speech, reports "Captured ✓" — and delivers nothing.
        logger.warn(
            "\n*** keystore.properties not found — signing with the DEBUG key. ***\n" +
                "*** This APK will NOT deliver captures to the phone app.      ***\n"
        )
    }
}

android {
    // namespace (source package / R class) intentionally stays .wear; only applicationId must
    // match the phone app for Data Layer delivery.
    namespace = "com.yshah.alfred.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yshah.alfred"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
        buildTypes.all { signingConfig = signingConfigs.getByName("release") }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // android.util.Log is a stub in JVM unit tests and throws unless it returns defaults.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // play-services-wearable drags in androidx.fragment 1.1.0, and nothing else on this
    // classpath raises it. Fragment < 1.3.0 mishandles ActivityResult permission callbacks —
    // which is exactly how the mic permission is requested — and lint fails the release build
    // over it. A constraint rather than a dependency: the floor matters, the artifact isn't used.
    constraints {
        implementation(libs.androidx.fragment)
    }

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.play.services.wearable)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
