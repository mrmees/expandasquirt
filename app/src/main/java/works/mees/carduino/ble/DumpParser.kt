package works.mees.carduino.ble

/**
 * Parses the periodic dump emitted by the Carduino over BLE NUS at 5 Hz.
 *
 * Wire format (V4X-DESIGN.md §6.2 / DESIGN.md §6.2):
 *
 * ```
 * [seq=142 ready=1 health=0x1F]
 *   oilT  =  185.2 °F   ok
 *   oilP  =   58.4 PSI  ok
 *   fuelP =   46.1 PSI  ok
 *   preP  =   97.8 kPa  ok
 *   postT =  142.6 °F   ok
 * ```
 *
 * Lines arrive one at a time via the BLE `lines` flow. Feed each into
 * [feed]; when a complete frame has been assembled, [feed] returns the
 * [DumpFrame]. Otherwise it returns null.
 *
 * The parser is forgiving:
 *  - Lines that don't match either the header or a sensor row are ignored
 *    (so other BLE output — banner, command echoes, error lines — passes
 *    through without poisoning the parse state).
 *  - If a new header arrives mid-frame, the in-progress frame is dropped
 *    and parsing restarts.
 *  - Unknown sensor names are ignored; the frame completes when all five
 *    expected sensors have arrived.
 */
class DumpParser {
    private var pending: DumpHeader? = null
    private val readings = mutableMapOf<String, SensorReading>()

    fun feed(line: String): DumpFrame? {
        // Header line restarts the frame.
        headerRegex.matchEntire(line)?.let { m ->
            pending = DumpHeader(
                seq = m.groupValues[1].toInt(),
                ready = m.groupValues[2] == "1",
                healthBitmask = m.groupValues[3].toInt(16),
            )
            readings.clear()
            return null
        }

        val header = pending ?: return null

        sensorRegex.matchEntire(line)?.let { m ->
            val name = m.groupValues[1]
            if (name !in EXPECTED_SENSORS) return null  // ignore unknown
            readings[name] = SensorReading(
                name = name,
                value = m.groupValues[2].toDouble(),
                unit = m.groupValues[3],
                healthOk = m.groupValues[4] == "ok",
            )
            if (readings.size == EXPECTED_SENSORS.size) {
                val frame = DumpFrame(
                    seq = header.seq,
                    ready = header.ready,
                    healthBitmask = header.healthBitmask,
                    readings = readings.toMap(),
                )
                pending = null
                readings.clear()
                return frame
            }
        }

        return null
    }

    /** Discard any in-progress frame. Useful when re-subscribing or recovering from disconnect. */
    fun reset() {
        pending = null
        readings.clear()
    }

    private data class DumpHeader(val seq: Int, val ready: Boolean, val healthBitmask: Int)

    companion object {
        val EXPECTED_SENSORS = setOf("oilT", "oilP", "fuelP", "preP", "postT")

        // [seq=N ready=0|1 health=0xHH]
        private val headerRegex =
            Regex("""\[seq=(\d+)\s+ready=([01])\s+health=0x([0-9A-Fa-f]+)\]""")

        // <whitespace>name = value unit health
        // unit may contain non-ASCII (°), name is alphanumeric, health is any
        // non-whitespace token (firmware emits "ok"/"FAULT"; tests cover "--"
        // as a defensive case for any future placeholder).
        private val sensorRegex =
            Regex("""\s*(\w+)\s*=\s*(-?\d+\.?\d*)\s+(\S+)\s+(\S+)\s*""")
    }
}

data class SensorReading(
    val name: String,
    val value: Double,
    val unit: String,
    val healthOk: Boolean,
)

data class DumpFrame(
    val seq: Int,
    val ready: Boolean,
    val healthBitmask: Int,
    val readings: Map<String, SensorReading>,
)
