plugins {
    id("com.android.test")
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
        create("loop") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            // Libraries do not have a loop variant.
            matchingFallbacks += listOf("release")
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
