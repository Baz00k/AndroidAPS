import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath(libs.com.android.tools.build)
        classpath(libs.com.google.gms)

        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.allopen)
        classpath(libs.kotlin.serialization)
    }
}

plugins {
    alias(libs.plugins.klint)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler) apply false
    id(libs.plugins.android.test.get().pluginId) apply false
    alias(libs.plugins.legacy.kapt) apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            jvmTarget.set(Versions.jvmTarget)
        }
    }
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile> {
            val compilerArgs = options.compilerArgs
            compilerArgs.add("-Xlint:deprecation")
            compilerArgs.add("-Xlint:unchecked")
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")
}

// Align Gradle's coverage agents and reports with the Android coverage DSL after AGP configuration.
gradle.projectsEvaluated {
    allprojects {
        extensions.configure<JacocoPluginExtension> {
            toolVersion = Versions.jacoco
        }
    }
}

apply(from = "jacoco_aggregation.gradle.kts")

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("unitTest") {
    group = "verification"
    description = "Run FullDebug/Debug Android unit tests and JVM module tests."
    dependsOn(subprojects.map { project ->
        project.tasks.withType<Test>().named {
            it in setOf("testFullDebugUnitTest", "testDebugUnitTest", "test")
        }
    })
}
