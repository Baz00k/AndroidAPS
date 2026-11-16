package app.aaps.ui.dialogs

import org.junit.jupiter.api.Test

class FillDialogTest {

    // The fragment manager re-creates an open sheet after rotation through this constructor.
    @Test
    fun `the sheet can be re-created after a configuration change`() {
        FillDialog::class.java.getConstructor()
    }
}
