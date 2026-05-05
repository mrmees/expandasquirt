package works.mees.carduino.ble

import java.util.UUID

/**
 * Nordic UART Service UUIDs as advertised by the Carduino's BLE peripheral.
 * Pinned in V4X-DESIGN.md §6.7 (carried over from DESIGN.md). Standard NUS
 * UUIDs are used so generic BLE serial apps (nRF Connect, Serial Bluetooth
 * Terminal) can also talk to the device.
 */
object NusUuids {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    /** TX from device → app (notify) */
    val TX: UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    /** RX from app → device (write) */
    val RX: UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    /** Standard Client Characteristic Configuration Descriptor */
    val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
