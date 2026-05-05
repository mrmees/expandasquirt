package works.mees.carduino.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // Autoreconnect is active only for a remembered target MAC after a normal
    // connect request. Peer disconnects and synchronous connect failures enter
    // the backoff cycle; explicit user disconnects suppress it and clear target.
    // Activity ON_PAUSE cancels pending retries without clearing target, and
    // ON_RESUME restarts the cycle if the link is still down. The public
    // connect() path resets attempts; internal retries preserve the counter
    // until a service-discovered connection proves the link is healthy.
    private val scope: CoroutineScope = MainScope()

    // Backoff schedule (ms): immediate, 1s, 5s, 15s, 30s, 60s, then 60s ceiling.
    private val backoff = listOf(0L, 1_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    private var attempt = 0
    private var paused = false
    private var targetMac: String? = null
    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null

    fun connect(mac: String) {
        targetMac = mac
        userInitiatedDisconnect = false
        attempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        attemptConnect(mac)
    }

    private fun attemptConnect(mac: String) {
        if (_state.value is BleState.Connecting || _state.value is BleState.Connected) {
            // Already busy; skip silently.
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
            // Treat synchronous failures as triggering the backoff cycle, same
            // as an asynchronous STATE_DISCONNECTED event.
            if (!userInitiatedDisconnect) scheduleReconnect(mac)
        }
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        targetMac = null
        reconnectJob?.cancel()
        reconnectJob = null
        gatt?.disconnect()
    }

    /** Called when the host Activity goes to ON_PAUSE -- stop scheduling reconnects. */
    fun pauseReconnect() {
        paused = true
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Called when the host Activity returns ON_RESUME -- resume reconnect cycle if appropriate. */
    fun resumeReconnect() {
        if (!paused) return
        paused = false
        val mac = targetMac ?: return
        if (userInitiatedDisconnect) return
        val s = _state.value
        if (s is BleState.Connecting || s is BleState.Connected) return
        // Restart the backoff fresh on resume; the situation may have changed
        // while backgrounded.
        attempt = 0
        scheduleReconnect(mac)
    }

    private fun scheduleReconnect(mac: String) {
        if (paused || userInitiatedDisconnect) return
        if (mac != targetMac) return  // staleness check; user may have moved on
        reconnectJob?.cancel()
        val delayMs = backoff.getOrElse(attempt) { 60_000L }
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            // Re-check state right before reconnecting; conditions may have
            // changed during the delay.
            if (paused || userInitiatedDisconnect) return@launch
            if (mac != targetMac) return@launch
            attemptConnect(mac)
        }
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
                    val mac = targetMac
                    if (mac != null && !userInitiatedDisconnect && !paused) {
                        scheduleReconnect(mac)
                    }
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
            attempt = 0
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
