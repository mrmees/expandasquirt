package works.mees.carduino.ui

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class OtaPercentEncodeTest {

    @Test fun asciiAlphanumericPassesThroughUnchanged() {
        assertEquals("abcXYZ012", percentEncode("abcXYZ012"))
    }

    @Test fun spaceIsPercentEncoded() {
        assertEquals("hello%20world", percentEncode("hello world"))
    }

    @Test fun specialCharsArePercentEncoded() {
        assertEquals("%3B%3A%2C%2F%3F%23%5B%5D%40", percentEncode(";:,/?#[]@"))
    }

    @Test fun utf8MultibyteIsPercentEncoded() {
        assertEquals("%C3%A9", percentEncode("é"))
    }

    @Test fun emptyStringStaysEmpty() {
        assertEquals("", percentEncode(""))
    }

    @Test fun passwordGeneratorReturnsSixteenAlphanumericChars() {
        val password = generateOtaPassword()

        assertEquals(16, password.length)
        assertTrue(password.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' })
    }

    @Test fun passwordGeneratorReturnsDifferentValues() {
        assertNotEquals(generateOtaPassword(), generateOtaPassword())
    }
}
