plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "app.aaps.libre3"

    // Pinned deliberately: this is the toolchain the vendored core was verified
    // against (VENDOR.md). Left unset, AGP silently downloads and uses its own
    // default NDK, so what ships would not be what was validated.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Single-device build: the Pixel 7 is arm64 only. Upstream Juggluco ships
        // four ABIs; building one keeps the ~600 KB whitebox payload from being
        // multiplied across ABIs we will never run.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // The whitebox tables do not benefit from compression and the loader is
        // faster mapping them straight out of the APK.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // Framing and the security state machine are deliberately free of Android types so they
    // can be exercised on the JVM — the crypto itself is covered separately by the byte-for-byte
    // conformance harness in src/test/l3_replay.py.
    testImplementation(libs.org.junit.jupiter)
    testImplementation(libs.com.google.truth)
}
