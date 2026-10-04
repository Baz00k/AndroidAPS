import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath(libs.com.android.tools.build)
        classpath(libs.com.google.gms)

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files

        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.allopen)
        classpath(libs.kotlin.serialization)
    }
}

plugins {
    alias(libs.plugins.klint)
    alias(libs.plugins.moduleDependencyGraph)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler) apply false
    id(libs.plugins.android.test.get().pluginId) apply false
    id(libs.plugins.kotlin.android.get().pluginId) apply false
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
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            freeCompilerArgs.add("-Xjvm-default=all") //Support @JvmDefault
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

// Setup all reports aggregation
apply(from = "jacoco_aggregation.gradle.kts")

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}

// Android modules use variant-specific tests; plain JVM modules use test.
tasks.register("unitTest") {
    group = "verification"
    description = "Run FullDebug/Debug Android unit tests and JVM module tests."
    dependsOn(subprojects.map { project ->
        project.tasks.withType<Test>().matching {
            it.name in setOf("testFullDebugUnitTest", "testDebugUnitTest", "test")
        }
    })
}
