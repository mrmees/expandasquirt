package works.mees.expandasquirt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class ScannedDevice(val mac: String, val name: String, val rssi: Int)

@SuppressLint("MissingPermission")
fun scanForExpandasquirts(ctx: Context) = callbackFlow {
    val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter
        .bluetoothLeScanner

    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (name == "EXPANDASQUIRT-v4") {
                trySend(ScannedDevice(result.device.address, name, result.rssi))
            }
        }
    }

    scanner?.startScan(cb)
    awaitClose { scanner?.stopScan(cb) }
}
