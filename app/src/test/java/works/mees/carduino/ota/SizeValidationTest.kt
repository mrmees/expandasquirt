package works.mees.carduino.ota

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeValidationTest {

    @Test fun acceptsCurrentSketchSize() {
        // v4 baseline per HANDOFF.md / DESIGN.md
        assertEquals(SizeCheck.Ok, validateSketchSize(104_880))
    }

    @Test fun acceptsAtMaxBoundary() {
        assertEquals(SizeCheck.Ok, validateSketchSize(CARDUINO_R4_MAX_SKETCH_BYTES.toLong()))
    }

    @Test fun rejectsOneByteOverMax() {
        val r = validateSketchSize(CARDUINO_R4_MAX_SKETCH_BYTES + 1L) as SizeCheck.TooLarge
        assertEquals(CARDUINO_R4_MAX_SKETCH_BYTES + 1L, r.bytes)
        assertEquals(CARDUINO_R4_MAX_SKETCH_BYTES, r.max)
    }

    @Test fun rejectsClearlyOversize() {
        val r = validateSketchSize(200_000) as SizeCheck.TooLarge
        assertEquals(200_000L, r.bytes)
    }

    @Test fun rejectsZero() {
        assertEquals(SizeCheck.Empty, validateSketchSize(0))
    }

    @Test fun rejectsNegative() {
        // Some content providers can return -1 for unknown size
        assertEquals(SizeCheck.Empty, validateSketchSize(-1L))
    }

    @Test fun acceptsTinyTestSketch() {
        // Task 53's test-sketch is ~52 KB
        assertEquals(SizeCheck.Ok, validateSketchSize(52_000))
    }

    @Test fun toLargeContainsByteCount() {
        // The UI surfaces the byte count in the error message — make sure it's preserved
        val r = validateSketchSize(150_000) as SizeCheck.TooLarge
        assertTrue(r.bytes > 0)
        assertEquals(150_000L, r.bytes)
    }
}
