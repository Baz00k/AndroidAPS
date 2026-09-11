import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipFile

/**
 * Checks resolved JVM call targets, including generated classes. A direct call to a [BluetoothGatt]
 * write API is allowed only inside the correspondingly named `writeCharacteristic`/`writeDescriptor`
 * dispatch method of [YpsoBleManager], and only when that method carries [YpsoGuardedWrite]. Method
 * references/handles to those APIs are rejected entirely: the guard cannot prove they are invoked
 * inside the guarded boundary, so they must not escape it.
 */
abstract class VerifyGattWriteOwnership : DefaultTask() {
    @get:Classpath abstract val jars: ListProperty<RegularFile>
    @get:Classpath abstract val directories: ListProperty<Directory>

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        fun inspect(bytes: ByteArray) {
            val reader = ClassReader(bytes)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        private var guarded = false

                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                            if (descriptor == GUARD_ANNOTATION) guarded = true
                            return null
                        }

                        fun checkCall(owner: String, target: String, viaHandle: Boolean) {
                            if (owner != "android/bluetooth/BluetoothGatt" || target !in setOf("writeCharacteristic", "writeDescriptor")) return
                            if (viaHandle) {
                                violations.add("${reader.className}.$name creates a method reference to BluetoothGatt.$target, which can escape the guarded dispatch boundary")
                                return
                            }
                            if (reader.className != "app/aaps/pump/ypsopump/ble/YpsoBleManager" || !guarded || name.substringBefore('$') != target) {
                                violations.add("${reader.className}.$name calls BluetoothGatt.$target outside its guarded dispatch method")
                            }
                        }

                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = checkCall(owner, name, false)
                        override fun visitLdcInsn(value: Any?) {
                            if (value is Handle) checkCall(value.owner, value.name, true)
                        }
                        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
                            bootstrapMethodArguments.filterIsInstance<Handle>().forEach { checkCall(it.owner, it.name, true) }
                        }
                    }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
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
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
    }

    companion object {
        private const val GUARD_ANNOTATION = "Lapp/aaps/pump/ypsopump/ble/YpsoGuardedWrite;"
    }
}
