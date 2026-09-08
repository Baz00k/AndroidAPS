plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.com.android.tools.build)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.allopen)
    implementation("org.ow2.asm:asm:9.8")
    testImplementation("junit:junit:4.13.2")
}
