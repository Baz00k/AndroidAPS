plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

val generateQualificationSession by tasks.registering {
    val production = file("../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/PumpSession.kt")
    val qualification = file("../qualification/src/main/qualification/PumpSessionQualification.ktfrag")
    val output = layout.buildDirectory.file("generated/qualification-session/app/aaps/pump/ypsopump/crypto/PumpSession.kt")
    inputs.files(production, qualification)
    outputs.file(output)
    doLast {
        val marker = "    // QUALIFICATION_METHODS_INSERTION_POINT"
        val source = production.readText()
        check(source.windowed(marker.length).count { it == marker } == 1) {
            "Production PumpSession qualification insertion point is missing or ambiguous"
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(source.replace(marker, qualification.readText().trimEnd()))
        }
    }
}

val syncSessionSources by tasks.registering(Sync::class) {
    from("../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/SessionCrypto.kt")
    from("../../src/main/kotlin/app/aaps/pump/ypsopump/crypto/SessionJournal.kt")
    from(generateQualificationSession)
    into(layout.buildDirectory.dir("generated/session-driver"))
}

android {
    namespace = "app.aaps.ypso.sessionbench"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.aaps.ypso.sessionbench"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/session-driver"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(syncSessionSources)
}

dependencies {
    implementation("javax.inject:javax.inject:1")
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
