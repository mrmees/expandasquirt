package works.mees.carduino.ota

/**
 * Size validation for sketch `.bin` files before they're pushed via OTA.
 *
 * The R4 WiFi has 256 KB of internal flash; JAndrassy/InternalStorageRenesas
 * splits it in half, putting the active sketch in the lower 128 KB and using
 * the upper 128 KB as the OTA staging area. The exact `maxSize()` returned
 * by the library is page-aligned slightly under 128 KB.
 *
 * We size-gate at the app *before* push so the user gets an immediate error
 * (with the actual byte count) rather than waiting for the device to reply
 * with HTTP 413 after a multi-second upload.
 *
 * See V4X-DESIGN.md §5.6 and prototypes/ota_arduinoota/notes-protocol.md §3.1.
 */

const val CARDUINO_R4_MAX_SKETCH_BYTES = 130_048  // ~half of 256 KB, page-aligned

sealed class SizeCheck {
    object Ok : SizeCheck()
    data class TooLarge(val bytes: Long, val max: Int) : SizeCheck()
    object Empty : SizeCheck()
}

fun validateSketchSize(bytes: Long): SizeCheck = when {
    bytes <= 0L -> SizeCheck.Empty
    bytes > CARDUINO_R4_MAX_SKETCH_BYTES -> SizeCheck.TooLarge(bytes, CARDUINO_R4_MAX_SKETCH_BYTES)
    else -> SizeCheck.Ok
}
