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
        "../../src/main/kotlin/app/aaps/pump/ypsopump/data/YpsoBasalSchedule.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/data/YpsoProfileReadback.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/provisioning/YpsoSessionDocument.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/provisioning/YpsoOwnershipHandoff.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoFraming.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoGlb.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoCrc.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoCommand.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/YpsoCommandCodes.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/commands/BolusCommand.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/commands/StatusCommand.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/comm/commands/TbrCommand.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/history/YpsoHistoryEntry.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/history/YpsoHistoryContract.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/history/YpsoBolusPumpIdentity.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/history/YpsoPumpLocalTime.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/bolus/YpsoBolusAttempt.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/bolus/YpsoBolusAttemptFileStore.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/bolus/YpsoBolusRequestValidator.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoWritePolicy.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoAuthentication.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoCommandReadiness.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoSerializedWriteTransport.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoWriteAccounting.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoBolusWriteCoordinator.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoTbrWriteCoordinator.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/tbr/YpsoTbrRequest.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoHistorySelectorCoordinator.kt",
        "../../src/main/kotlin/app/aaps/pump/ypsopump/ble/YpsoProfileSelectorCoordinator.kt",
    )
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
val syncSharedDriverSources by tasks.registering(Sync::class) {
    from(sharedDriverSources) { exclude("PumpSession.kt") }
    from("../qualification/src/main/kotlin")
    from(generateQualificationSession)
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
    testRuntimeOnly("net.java.dev.jna:jna:5.14.0")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
