import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.io.File

class VerifyGattWriteOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only the annotated dispatch method may call BluetoothGatt writes`() {
        for (target in listOf("writeCharacteristic", "writeDescriptor")) {
            checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "writeCharacteristic\$ypsopump_fullDebug", target, guarded = true)
            // A similarly named helper without the guard annotation fails, even in the owner class.
            assertThrows(GradleException::class.java) {
                checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "writeCharacteristic\$unsafe", target)
            }
            assertThrows(GradleException::class.java) {
                checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", target, target)
            }
            assertThrows(GradleException::class.java) { checkClass("driver/Other", "dispatch", target, guarded = true) }
            assertThrows(GradleException::class.java) { checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "bypass", target) }
            checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "dispatch", target, reference = true, guarded = true)
            assertThrows(GradleException::class.java) {
                checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "dispatch", target, reference = true)
            }
            assertThrows(GradleException::class.java) { checkClass("driver/GeneratedReference", "invoke", target, reference = true) }
        }
        checkClass("driver/Other", "dispatch", "writeCharacteristic", targetOwner = "unrelated/LocalStore")
    }

    private fun checkClass(
        owner: String,
        method: String,
        target: String,
        reference: Boolean = false,
        guarded: Boolean = false,
        targetOwner: String = "android/bluetooth/BluetoothGatt"
    ) {
        val directory = temporary.newFolder()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null).apply {
            if (guarded) visitAnnotation("Lapp/aaps/pump/ypsopump/ble/YpsoGuardedWrite;", false)?.visitEnd()
            visitCode()
            if (reference) visitLdcInsn(Handle(Opcodes.H_INVOKEVIRTUAL, targetOwner, target, "()V", false))
            else visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetOwner, target, "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        File(directory, "Fixture.class").writeBytes(writer.toByteArray())
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        project.tasks.create("verify", VerifyGattWriteOwnership::class.java).apply {
            jars.set(emptyList())
            directories.set(listOf(project.layout.projectDirectory.dir(directory.absolutePath)))
            verify()
        }
    }
}
