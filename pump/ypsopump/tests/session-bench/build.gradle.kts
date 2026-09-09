plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
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
    sourceSets["main"].java.srcDir("../../src/main/kotlin/app/aaps/pump/ypsopump/crypto")
    sourceSets["main"].java.exclude("**/KeyExchange.kt")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    implementation("javax.inject:javax.inject:1")
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
