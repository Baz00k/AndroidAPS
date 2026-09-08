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
    fun `guarded owner dispatch passes while foreign calls and references fail`() {
        for (target in listOf("writeCharacteristic", "writeDescriptor")) {
            checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "$target\$ypsopump_fullDebug", target)
            assertThrows(GradleException::class.java) { checkClass("driver/Other", "dispatch", target) }
            assertThrows(GradleException::class.java) { checkClass("app/aaps/pump/ypsopump/ble/YpsoBleManager", "bypass", target) }
            assertThrows(GradleException::class.java) { checkClass("driver/GeneratedReference", "invoke", target, reference = true) }
        }
        checkClass("driver/Other", "dispatch", "writeCharacteristic", targetOwner = "unrelated/LocalStore")
    }

    private fun checkClass(owner: String, method: String, target: String, reference: Boolean = false, targetOwner: String = "android/bluetooth/BluetoothGatt") {
        val directory = temporary.newFolder()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null).apply {
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
