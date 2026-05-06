package works.mees.expandasquirt.ota

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiQrParserTest {

    @Test fun parsesPlainPayload() {
        val r = parseWifiQr("WIFI:S:MyNet;T:WPA;P:hunter2;;") as WifiQrResult.Ok
        assertEquals("MyNet", r.creds.ssid)
        assertEquals("hunter2", r.creds.password)
        assertEquals("WPA", r.creds.security)
        assertFalse(r.creds.hidden)
    }

    @Test fun parsesReorderedKeys() {
        val r = parseWifiQr("WIFI:T:WPA;S:MyNet;P:hunter2;;") as WifiQrResult.Ok
        assertEquals("MyNet", r.creds.ssid)
        assertEquals("hunter2", r.creds.password)
        assertEquals("WPA", r.creds.security)
    }

    @Test fun parsesNopass() {
        val r = parseWifiQr("WIFI:S:Net;T:nopass;;") as WifiQrResult.Ok
        assertEquals("Net", r.creds.ssid)
        assertEquals(null, r.creds.password)
        assertEquals("nopass", r.creds.security)
    }

    @Test fun parsesHiddenFlag() {
        val r = parseWifiQr("WIFI:S:H;T:WPA;P:p;H:true;;") as WifiQrResult.Ok
        assertTrue(r.creds.hidden)
    }

    @Test fun parsesEscapedSemicolonInSsid() {
        val r = parseWifiQr("WIFI:S:Net With\\;Semicolon;T:WPA;P:abc;;") as WifiQrResult.Ok
        assertEquals("Net With;Semicolon", r.creds.ssid)
    }

    @Test fun parsesEscapedColonAndCommaInPassword() {
        val r = parseWifiQr("WIFI:T:WPA;S:N;P:p\\;a\\:s\\,s;;") as WifiQrResult.Ok
        assertEquals("p;a:s,s", r.creds.password)
    }

    @Test fun parsesLowercasePrefix() {
        val r = parseWifiQr("wifi:S:Net;;") as WifiQrResult.Ok
        assertEquals("Net", r.creds.ssid)
    }

    @Test fun rejectsMissingPrefix() {
        val r = parseWifiQr("S:Net;;") as WifiQrResult.Err
        assertEquals("not a wifi qr", r.reason)
    }

    @Test fun rejectsMissingSsid() {
        val r = parseWifiQr("WIFI:T:WPA;P:abc;;") as WifiQrResult.Err
        assertEquals("missing ssid", r.reason)
    }

    @Test fun ignoresUnknownKey() {
        val r = parseWifiQr("WIFI:S:N;X:foo;P:abc;;") as WifiQrResult.Ok
        assertEquals("N", r.creds.ssid)
        assertEquals("abc", r.creds.password)
    }
}
