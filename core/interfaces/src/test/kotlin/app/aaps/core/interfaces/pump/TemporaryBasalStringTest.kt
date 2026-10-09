package app.aaps.core.interfaces.pump

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class TemporaryBasalStringTest {

    @Test
    fun `Russian temporary basal description formats the percentage and duration`() {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values-ru-rRU/strings.xml"))
            .getElementsByTagName("string")
        val template = (0 until nodes.length).map { nodes.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "temp_basal_percent_rate" }
            .textContent.replace("\\'", "'")

        val description = String.format(Locale.US, template, 150.0, "12:34", 5, 30)

        assertThat(description).isEqualTo("150.00\u00a0% @12:34\u00a05/30'")
    }
}
