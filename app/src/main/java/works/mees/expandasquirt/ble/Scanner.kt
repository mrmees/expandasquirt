package works.mees.expandasquirt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class ScannedDevice(val mac: String, val name: String, val rssi: Int)

private val NUS_SERVICE_UUID =
    ParcelUuid.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private const val EXPECTED_NAME = "EXPANDASQUIRT"

@SuppressLint("MissingPermission")
fun scanForExpandasquirts(ctx: Context) = callbackFlow {
    val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter
        .bluetoothLeScanner

    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Prefer scanRecord.deviceName (fresh from the current advertisement
            // / scan response) over result.device.name (Android's sticky cache,
            // which may still hold stale names like CARDUINO-v4 from earlier
            // firmware revisions). Fall back to the expected brand name since
            // we already filtered on the NUS service UUID below.
            val name = result.scanRecord?.deviceName
                ?: result.device.name
                ?: EXPECTED_NAME
            trySend(ScannedDevice(result.device.address, name, result.rssi))
        }
    }

    val filters = listOf(
        ScanFilter.Builder()
            .setServiceUuid(NUS_SERVICE_UUID)
            .build()
    )
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    scanner?.startScan(filters, settings, cb)
    awaitClose { scanner?.stopScan(cb) }
}
