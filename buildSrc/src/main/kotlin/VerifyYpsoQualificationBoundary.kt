import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipFile

/** Rejects executable qualification policy in every compiled Ypso production variant. */
abstract class VerifyYpsoQualificationBoundary : DefaultTask() {
    @get:Classpath abstract val jars: ListProperty<RegularFile>
    @get:Classpath abstract val directories: ListProperty<Directory>

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        fun inspect(bytes: ByteArray) {
            val reader = ClassReader(bytes)
            if (reader.className in forbiddenClasses) {
                violations += "${reader.className} is qualification-only but was compiled into the Ypso production artifact"
            }
            if (reader.superName == WRITE_ACCOUNTING) {
                violations += "${reader.className} subclasses production write accounting; reservation policy adapters are qualification-only"
            }
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (reader.className == PUMP_SESSION && forbiddenSessionMethods.any { name.startsWith(it) }) {
                        violations += "${reader.className}.$name is an executable qualification operation in the Ypso production artifact"
                    }
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        directories.get().forEach { directory ->
            directory.asFile.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { inspect(it.readBytes()) }
        }
        jars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    zip.getInputStream(entry).use { inspect(it.readBytes()) }
                }
            }
        }
        if (violations.isNotEmpty()) throw GradleException(violations.distinct().joinToString("\n"))
    }

    companion object {
        private const val PUMP_SESSION = "app/aaps/pump/ypsopump/crypto/PumpSession"
        private const val WRITE_ACCOUNTING = "app/aaps/pump/ypsopump/ble/YpsoWriteAccounting"
        private val forbiddenClasses = setOf(
            "app/aaps/pump/ypsopump/ble/YpsoBenchWriteCoordinator",
            "app/aaps/pump/ypsopump/ble/YpsoQualificationWriteAccounting",
            "app/aaps/pump/ypsopump/ble/YpsoQualificationWritePolicy",
            "app/aaps/pump/ypsopump/ble/YpsoArtifactPolicy",
        )
        private val forbiddenSessionMethods = setOf(
            "provisionBenchWriteBaseline",
            "reserveBench",
            "recordBench",
            "benchAmbiguityConvergenceReady",
            "benchSettingsCounterRecoveryReady",
            "benchSettingsCounterJumpReady",
        )
    }
}
