package works.mees.carduino.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** High-level state of the BLE link to a Carduino. */
sealed class BleState {
    object Idle : BleState()
    data class Connecting(val mac: String) : BleState()
    data class Connected(val mac: String) : BleState()
    data class Failed(val mac: String?, val reason: String) : BleState()
}

/**
 * BLE central wrapping the Carduino's NUS profile. Parses notifications from
 * the TX characteristic into newline-framed lines (the firmware always uses
 * `\r\n` terminators per V4X-DESIGN.md §6.7) and exposes them via [lines].
 *
 * Writes to the RX characteristic are serialized through `onCharacteristicWrite`
 * via a one-slot queue — issuing a new write before the previous completes
 * will silently lose data on Samsung/some other stacks. Per IMPLEMENTATION-
 * PLAN.md Task 64 (codex review fix).
 *
 * MTU negotiated up to 247 bytes after service discovery. Lower-MTU clients
 * still work; the firmware handles fragmentation in its periodic-dump output.
 */
@SuppressLint("MissingPermission")
class CarduinoBleClient(private val ctx: Context) {

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val lineBuf = StringBuilder()

    // Async-write completion signal. One write outstanding at a time; the next
    // write blocks until onCharacteristicWrite delivers its status.
    private val writeCompletion = LinkedBlockingQueue<Int>(1)
    private var negotiatedMtu = 23  // BLE 4.x default; bumped via onMtuChanged

    fun connect(mac: String) {
        if (_state.value is BleState.Connecting || _state.value is BleState.Connected) {
            // Already busy; let the existing connection finish.
            return
        }
        _state.value = BleState.Connecting(mac)
        try {
            val device = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter
                .getRemoteDevice(mac)
            gatt = device.connectGatt(ctx, /* autoConnect = */ false, callback)
        } catch (e: Exception) {
            _state.value = BleState.Failed(mac, e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    /**
     * Send a line (terminator added automatically). Returns true on success,
     * false if the BLE link is down or any chunk write fails.
     */
    suspend fun writeLine(text: String): Boolean = withContext(Dispatchers.IO) {
        val char = rxChar ?: return@withContext false
        val activeGatt = gatt ?: return@withContext false

        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        val payload = (negotiatedMtu - 3).coerceAtLeast(20)  // ATT overhead is 3 bytes

        var sent = 0
        while (sent < bytes.size) {
            val end = minOf(sent + payload, bytes.size)
            writeCompletion.clear()
            char.value = bytes.copyOfRange(sent, end)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (!activeGatt.writeCharacteristic(char)) return@withContext false
            // Wait for onCharacteristicWrite (max 2 sec per chunk)
            val status = writeCompletion.poll(2, TimeUnit.SECONDS)
                ?: return@withContext false
            if (status != BluetoothGatt.GATT_SUCCESS) return@withContext false
            sent = end
        }
        true
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    rxChar = null
                    lineBuf.clear()
                    _state.value = BleState.Idle
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(NusUuids.SERVICE) ?: run {
                _state.value = BleState.Failed(g.device.address, "NUS service not found")
                return
            }
            rxChar = service.getCharacteristic(NusUuids.RX)
            val tx = service.getCharacteristic(NusUuids.TX) ?: run {
                _state.value = BleState.Failed(g.device.address, "NUS TX characteristic not found")
                return
            }

            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(NusUuids.CCCD)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            cccd?.let { g.writeDescriptor(it) }

            // Request a larger MTU. Higher MTU = less per-chunk overhead during
            // the long maintenance command. Result delivered via onMtuChanged.
            g.requestMtu(247)

            _state.value = BleState.Connected(g.device.address)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // writeLine() blocks on this signal.
            writeCompletion.offer(status)
        }

        @Deprecated("Override the new (3-arg) variant on API 33+; both delegate here.")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotify(c, c.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(c, value)
        }
    }

    private fun handleNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != NusUuids.TX) return
        lineBuf.append(value.toString(Charsets.UTF_8))
        while (true) {
            val nl = lineBuf.indexOf('\n')
            if (nl < 0) break
            val line = lineBuf.substring(0, nl).trimEnd('\r')
            lineBuf.delete(0, nl + 1)
            _lines.tryEmit(line)
        }
    }
}
