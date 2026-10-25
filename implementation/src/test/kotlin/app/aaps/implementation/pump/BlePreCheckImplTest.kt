package app.aaps.implementation.pump

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import app.aaps.core.interfaces.pump.BlePreCheck.Result
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlePreCheckImplTest {

    @Mock lateinit var context: Context
    @Mock lateinit var activity: AppCompatActivity
    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var bluetoothManager: BluetoothManager
    @Mock lateinit var bluetoothAdapter: BluetoothAdapter
    @Mock lateinit var rh: ResourceHelper

    private lateinit var sut: BlePreCheckImpl

    @BeforeEach
    fun setUp() {
        whenever(activity.packageManager).thenReturn(packageManager)
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)).thenReturn(true)
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetoothManager)
        whenever(bluetoothManager.adapter).thenReturn(bluetoothAdapter)
        whenever(context.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
        sut = BlePreCheckImpl(context, rh, AAPSLoggerTest())
    }

    @Test
    fun `bluetooth off asks the user and reports it is disabled`() {
        whenever(bluetoothAdapter.isEnabled).thenReturn(false)

        val result = sut.prerequisitesCheck(activity)

        assertThat(result).isEqualTo(Result.BLUETOOTH_DISABLED)
        verify(activity).startActivity(any())
    }

    @Test
    fun `bluetooth on does not ask the user`() {
        whenever(bluetoothAdapter.isEnabled).thenReturn(true)

        val result = sut.prerequisitesCheck(activity)

        assertThat(result).isEqualTo(Result.READY)
        verify(activity, never()).startActivity(any())
    }
}
