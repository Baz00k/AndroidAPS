plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

val sharedDriverSources =
    files(
        "../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/PumpSession.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/SessionCrypto.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/SessionJournal.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/data/YpsoFirmwareVersion.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/provisioning/YpsoSessionDocument.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoFraming.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoGlb.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoCrc.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoWritePolicy.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoAuthentication.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoCommandReadiness.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoSerializedWriteTransport.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoBenchWriteCoordinator.kt",
    )
val syncSharedDriverSources by tasks.registering(Sync::class) {
    from(sharedDriverSources)
    into(layout.buildDirectory.dir("generated/shared-driver"))
}

android {
    namespace = "app.aaps.ypso.writebench"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.aaps.ypso.writebench"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/shared-driver"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(syncSharedDriverSources)
}

dependencies {
    implementation("javax.inject:javax.inject:1")
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
