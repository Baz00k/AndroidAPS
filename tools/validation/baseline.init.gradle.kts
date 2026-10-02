import groovy.json.JsonOutput
import org.gradle.api.tasks.testing.Test

// Explicit task paths avoid Gradle's ambiguous testDebugUnitTest abbreviation and include JVM modules.
gradle.projectsEvaluated {
    if (rootProject.findProject(":app") != null) {
        val tests = rootProject.allprojects.flatMap { project ->
            project.tasks.withType(Test::class.java).filter {
                it.name in setOf("test", "testDebugUnitTest", "testFullDebugUnitTest")
            }
        }.sortedBy { it.path }
        rootProject.tasks.register("validationBaselineInventory") {
            doLast {
                val output = rootProject.file(rootProject.property("validationEvidenceDir").toString())
                output.mkdirs()
                output.resolve("test-tasks.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(tests.map {
                    mapOf(
                        "task" to it.path,
                        "xml_dir" to it.reports.junitXml.outputLocation.get().asFile.absolutePath,
                        "class_dirs" to it.testClassesDirs.files.map { directory -> directory.absolutePath }
                    )
                })))
            }
        }
        rootProject.tasks.register("validationBaselineTests") {
            dependsOn(tests)
        }
    }
}
