/*
 * jandrassy_proto.ino — bench prototype for Task 53.
 *
 * Verifies that the JAndrassy/ArduinoOTA library actually works on R4 WiFi
 * with modem firmware 0.6.0 in a phone-hotspot topology. Joins the user's
 * phone hotspot in STA mode, opens an ArduinoOTA listener on port 65280,
 * and prints an alive heartbeat to USB serial so we can see when it's ready
 * for a curl push from a laptop on the same hotspot.
 *
 * Pass criteria (see prototypes/ota_arduinoota/README.md after running):
 *   1. Connects to hotspot, prints IP.
 *   2. Listener accepts a push from `curl -u arduino:testpw -X POST
 *      --data-binary @<path>/test-sketch.ino.bin http://<ip>:65280/sketch`
 *   3. Device reboots into the pushed sketch (test-sketch prints
 *      HELLO_FROM_OTA_TARGET continuously).
 *   4. mDNS responder is discoverable as carduino-v4-proto._arduino._tcp.local.
 *
 * Recovery: re-flash this sketch via USB to recover after a successful push
 * has booted into test-sketch.
 *
 * NOT production code — see V4X-DESIGN.md and IMPLEMENTATION-PLAN.md
 * Phase M for the production maintenance state machine that integrates this
 * library properly.
 */

#include <WiFiS3.h>
#include <ArduinoOTA.h>

#include "arduino_secrets.h"  // SECRET_SSID, SECRET_PASS — phone hotspot creds

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 3000) {
    // wait briefly for USB CDC to come up; don't block forever
  }

  Serial.println();
  Serial.print("WiFi.firmwareVersion(): ");
  Serial.println(WiFi.firmwareVersion());
  Serial.print("Connecting to hotspot \"");
  Serial.print(SECRET_SSID);
  Serial.println("\"...");

  WiFi.begin(SECRET_SSID, SECRET_PASS);
  unsigned long startMs = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - startMs > 30000) {
      Serial.println("\nTimed out joining hotspot. Check creds + hotspot is on. Halting.");
      while (1) { delay(1000); }
    }
    delay(200);
    Serial.print(".");
  }

  Serial.println();
  Serial.print("Connected. Local IP: ");
  Serial.println(WiFi.localIP());
  Serial.print("RSSI: ");
  Serial.print(WiFi.RSSI());
  Serial.println(" dBm");

  ArduinoOTA.begin(WiFi.localIP(), "carduino-v4-proto", "testpw", InternalStorage);
  Serial.println("ArduinoOTA listening on port 65280");
  Serial.println("mDNS service: carduino-v4-proto._arduino._tcp.local.");
  Serial.println();
  Serial.println("Push from a laptop on the same hotspot:");
  Serial.print("  curl -u arduino:testpw -X POST --data-binary @<path>/test-sketch.ino.bin http://");
  Serial.print(WiFi.localIP());
  Serial.println(":65280/sketch");
}

void loop() {
  ArduinoOTA.poll();

  static unsigned long lastBeat = 0;
  if (millis() - lastBeat >= 2000) {
    lastBeat = millis();
    Serial.print("alive  rssi=");
    Serial.print(WiFi.RSSI());
    Serial.print("  uptime=");
    Serial.print(millis() / 1000);
    Serial.println("s");
  }
}
