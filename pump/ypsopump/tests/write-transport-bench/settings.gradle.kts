// This standalone build lives below the main project, so AGP does not see its local.properties.
// Reuse the main project's SDK when one is configured there; keep an explicit bench SDK override.
val localSdk = file("local.properties")
val mainSdk = file("../../../../local.properties")
if (!localSdk.exists() && mainSdk.isFile) {
    localSdk.writeText(mainSdk.readText())
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "YpsoWriteTransportBench"
