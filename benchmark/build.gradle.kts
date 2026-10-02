plugins {
    id("com.android.test")
    id("kotlin-android")
}

android {
    namespace = "app.aaps.benchmark"
    compileSdk = Versions.compileSdk

    defaultConfig {
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Match the actual sideloaded FullLoop artifact; the instrumentation APK itself is debuggable.
        create("loop") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
        }
    }

    flavorDimensions += listOf("standard")
    productFlavors {
        create("full") { dimension = "standard" }
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "loop"
    }
}
