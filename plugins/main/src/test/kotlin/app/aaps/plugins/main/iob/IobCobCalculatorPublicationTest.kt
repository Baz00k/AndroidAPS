package app.aaps.plugins.main.iob

import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class IobCobCalculatorPublicationTest {

    @Test
    fun `replacement store has JVM volatile publication semantics`() {
        // Pin the cross-thread publication contract directly, rather than relying on a flaky
        // race test that may pass even when a reader is allowed to keep an obsolete reference.
        val storeField = IobCobCalculatorPlugin::class.java.getDeclaredField("ads")
        assertThat(Modifier.isVolatile(storeField.modifiers)).isTrue()
    }
}
