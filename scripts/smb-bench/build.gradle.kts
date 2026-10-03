// Measures, on a PC, the SMB reading of Nyxara with its own code: which part
// limits the rate. Run: ./gradlew -p scripts/smb-bench run, settings in bench.txt (see SmbBench.kt).
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

dependencies {
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.emc.ecs:nfs-client:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}


// The app's NAS code itself, not a copy: what is measured is what the app runs.
sourceSets {
    main {
        kotlin.srcDir("src")
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("bench/**", "android/**")
        kotlin.include("io/github/mkdevtests/umbra/nas/**", "io/github/mkdevtests/umbra/browse/MediaFiles.kt")
        kotlin.exclude("io/github/mkdevtests/umbra/nas/SourceStore.kt")
    }
}

application {
    mainClass.set("bench.SmbBenchKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
