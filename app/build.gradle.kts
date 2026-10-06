import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// API tokens live in local.properties (gitignored), never in the repo.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProperty(key: String) = "\"${localProperties.getProperty(key, "")}\""

// Bump for each GitHub release: the in-app updater compares it to the latest tag (v0.2.0).
val nyxaraVersion = "1.2.4"

base {
    // APK names: nyxara-debug.apk, nyxara-release-unsigned.apk
    archivesName.set("nyxara")
}

android {
    namespace = "io.github.mkdevtests.umbra"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.mkdevtests.umbra"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionName = nyxaraVersion
        // 0.2.0 -> 200, 1.12.3 -> 11203: always increasing, as Android requires.
        versionCode = nyxaraVersion.split(".").map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

        buildConfigField("String", "TMDB_TOKEN", localProperty("tmdb.token"))
        buildConfigField("String", "THETVDB_TOKEN", localProperty("thetvdb.token"))
        // Trakt app of the user (trakt.tv/oauth/applications); the secret is optional.
        buildConfigField("String", "TRAKT_CLIENT_ID", localProperty("trakt.clientId"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", localProperty("trakt.clientSecret"))
        buildConfigField("String", "OPENSUBTITLES_KEY", localProperty("opensubtitles.key"))

        // libmpv ships 4 ABIs (~100 MB): arm64 for the tablet, and 32-bit ARM for the
        // Android TVs, which mostly run a 32-bit system even on a 64-bit chip.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        debug {
            // Debug and release side by side on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Nyxara Debug")
            // Releases install as io.github.mkdevtests.umbra: not an update of this build.
            buildConfigField("boolean", "UPDATES", "false")
        }
        release {
            // Signed by scripts/build-nyxara-release.sh (zipalign + apksigner).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Nyxara")
            buildConfigField("boolean", "UPDATES", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Netty 3 (NFS client) ships these in every jar.
        resources.excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }

    testOptions {
        // android.util.Log and the like answer nothing instead of failing the JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    // Exported schemas, to write migrations once the database ships.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.libmpv)

    // NAS access: SMB2/3 client + localhost HTTP server feeding mpv.
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.nanohttpd)
    // NFSv3 sources: pure Java client.
    implementation(libs.nfsclient)

    // Metadata (TMDB) and artwork.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Library database, with full-text search.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
