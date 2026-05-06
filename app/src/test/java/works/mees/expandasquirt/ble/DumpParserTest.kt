package works.mees.expandasquirt.ble

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DumpParserTest {

    @Test fun parsesHappyPath() {
        val lines = listOf(
            "[seq=142 ready=1 health=0x1F]",
            "  oilT  =  185.2 °F   ok",
            "  oilP  =   58.4 PSI  ok",
            "  fuelP =   46.1 PSI  ok",
            "  preP  =   97.8 kPa  ok",
            "  postT =  142.6 °F   ok",
        )
        val parser = DumpParser()
        var frame: DumpFrame? = null
        for (line in lines) {
            val r = parser.feed(line)
            if (r != null) frame = r
        }
        assertNotNull(frame)
        assertEquals(142, frame.seq)
        assertTrue(frame.ready)
        assertEquals(0x1F, frame.healthBitmask)
        assertEquals(5, frame.readings.size)
        assertEquals(185.2, frame.readings["oilT"]!!.value, 0.01)
        assertEquals("PSI", frame.readings["oilP"]!!.unit)
        assertEquals("kPa", frame.readings["preP"]!!.unit)
        assertTrue(frame.readings["preP"]!!.healthOk)
    }

    @Test fun returnsNullUntilFrameComplete() {
        val parser = DumpParser()
        assertNull(parser.feed("[seq=1 ready=1 health=0x1F]"))
        assertNull(parser.feed("  oilT  =  100.0 °F   ok"))
        assertNull(parser.feed("  oilP  =   30.0 PSI  ok"))
        assertNull(parser.feed("  fuelP =   40.0 PSI  ok"))
        assertNull(parser.feed("  preP  =   90.0 kPa  ok"))
        // Final sensor completes the frame
        val frame = parser.feed("  postT =  120.0 °F   ok")
        assertNotNull(frame)
        assertEquals(1, frame.seq)
    }

    @Test fun ignoresUnrelatedLines() {
        val parser = DumpParser()
        // Banner/version line arriving on connect — should not affect parser
        assertNull(parser.feed("EXPANDASQUIRT-v4 version=4.1.0 build=abc123"))
        assertNull(parser.feed("reset=4 boot=42 last_err=00"))
        assertNull(parser.feed("> some command echo"))

        // Now a real frame
        assertNull(parser.feed("[seq=5 ready=1 health=0x1F]"))
        assertNull(parser.feed("  oilT  =  100.0 °F   ok"))
        assertNull(parser.feed("  oilP  =   30.0 PSI  ok"))
        assertNull(parser.feed("  fuelP =   40.0 PSI  ok"))
        assertNull(parser.feed("  preP  =   90.0 kPa  ok"))
        val frame = parser.feed("  postT =  120.0 °F   ok")
        assertNotNull(frame)
        assertEquals(5, frame.seq)
    }

    @Test fun newHeaderRestartsFrame() {
        val parser = DumpParser()
        // Partial frame
        parser.feed("[seq=1 ready=1 health=0x1F]")
        parser.feed("  oilT  =  100.0 °F   ok")
        parser.feed("  oilP  =   30.0 PSI  ok")

        // New header arrives — drop in-progress, restart
        parser.feed("[seq=2 ready=1 health=0x1F]")
        parser.feed("  oilT  =  101.0 °F   ok")
        parser.feed("  oilP  =   31.0 PSI  ok")
        parser.feed("  fuelP =   41.0 PSI  ok")
        parser.feed("  preP  =   91.0 kPa  ok")
        val frame = parser.feed("  postT =  121.0 °F   ok")
        assertNotNull(frame)
        assertEquals(2, frame.seq)
        assertEquals(101.0, frame.readings["oilT"]!!.value, 0.01)
    }

    @Test fun ignoresUnknownSensorName() {
        val parser = DumpParser()
        parser.feed("[seq=1 ready=1 health=0x1F]")
        parser.feed("  oilT  =  100.0 °F   ok")
        parser.feed("  oilP  =   30.0 PSI  ok")
        parser.feed("  fuelP =   40.0 PSI  ok")
        parser.feed("  preP  =   90.0 kPa  ok")
        // Unknown sensor — should be ignored, NOT count toward frame completion
        assertNull(parser.feed("  fooT  =   99.9 °C   ok"))
        // Real final sensor — should complete
        val frame = parser.feed("  postT =  120.0 °F   ok")
        assertNotNull(frame)
    }

    @Test fun parsesNotReady() {
        val parser = DumpParser()
        parser.feed("[seq=1 ready=0 health=0x00]")
        parser.feed("  oilT  =    0.0 °F   --")
        parser.feed("  oilP  =    0.0 PSI  --")
        parser.feed("  fuelP =    0.0 PSI  --")
        parser.feed("  preP  =    0.0 kPa  --")
        val frame = parser.feed("  postT =    0.0 °F   --")
        assertNotNull(frame)
        assertEquals(false, frame.ready)
        assertEquals(0x00, frame.healthBitmask)
        assertEquals(false, frame.readings["oilT"]!!.healthOk)
    }

    @Test fun parsesPartialHealthBitmask() {
        val parser = DumpParser()
        parser.feed("[seq=99 ready=1 health=0x0C]")
        parser.feed("  oilT  =  185.2 °F   --")
        parser.feed("  oilP  =   58.4 PSI  --")
        parser.feed("  fuelP =   46.1 PSI  ok")
        parser.feed("  preP  =   97.8 kPa  ok")
        val frame = parser.feed("  postT =  142.6 °F   --")
        assertNotNull(frame)
        assertEquals(0x0C, frame.healthBitmask)
        assertEquals(false, frame.readings["oilT"]!!.healthOk)
        assertEquals(true,  frame.readings["fuelP"]!!.healthOk)
    }

    @Test fun parsesNegativeValues() {
        val parser = DumpParser()
        parser.feed("[seq=1 ready=1 health=0x1F]")
        parser.feed("  oilT  =  -10.5 °F   ok")
        parser.feed("  oilP  =    0.0 PSI  ok")
        parser.feed("  fuelP =    0.0 PSI  ok")
        parser.feed("  preP  =   97.8 kPa  ok")
        val frame = parser.feed("  postT =  -22.3 °F   ok")
        assertNotNull(frame)
        assertEquals(-10.5, frame.readings["oilT"]!!.value, 0.01)
        assertEquals(-22.3, frame.readings["postT"]!!.value, 0.01)
    }

    @Test fun parsesIntegerLikeValues() {
        // Some sensors may emit "100.0" or "100" — accept both
        val parser = DumpParser()
        parser.feed("[seq=1 ready=1 health=0x1F]")
        parser.feed("  oilT  =  100 °F   ok")
        parser.feed("  oilP  =  30.0 PSI  ok")
        parser.feed("  fuelP =  40.0 PSI  ok")
        parser.feed("  preP  =  90.0 kPa  ok")
        val frame = parser.feed("  postT =  120 °F   ok")
        assertNotNull(frame)
        assertEquals(100.0, frame.readings["oilT"]!!.value, 0.01)
        assertEquals(120.0, frame.readings["postT"]!!.value, 0.01)
    }

    @Test fun resetClearsInProgressFrame() {
        val parser = DumpParser()
        parser.feed("[seq=1 ready=1 health=0x1F]")
        parser.feed("  oilT  =  100.0 °F   ok")

        parser.reset()

        // Sensors after reset (without a header) should be ignored
        assertNull(parser.feed("  oilP  =   30.0 PSI  ok"))
        assertNull(parser.feed("  fuelP =   40.0 PSI  ok"))
        assertNull(parser.feed("  preP  =   90.0 kPa  ok"))
        assertNull(parser.feed("  postT =  120.0 °F   ok"))
    }

    @Test fun rejectsMalformedHeader() {
        val parser = DumpParser()
        // Missing closing bracket — should not start a frame
        assertNull(parser.feed("[seq=1 ready=1 health=0x1F"))
        // Garbage in seq position
        assertNull(parser.feed("[seq=abc ready=1 health=0x1F]"))
        // Verify subsequent sensor lines do nothing (no header active)
        assertNull(parser.feed("  oilT  =  100.0 °F   ok"))
    }
}
