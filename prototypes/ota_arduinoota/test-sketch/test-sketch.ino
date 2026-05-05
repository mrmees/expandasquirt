/*
 * test-sketch.ino — minimal target for the OTA push in Task 53.
 *
 * After jandrassy_proto.ino is running on the R4 (joined to phone hotspot,
 * ArduinoOTA listener active), pushing this sketch's compiled .bin via:
 *
 *   curl -u arduino:testpw -X POST \
 *     -H "Content-Type: application/octet-stream" \
 *     --data-binary @<build-output>/test-sketch.ino.bin \
 *     http://<device-ip>:65280/sketch
 *
 * should result in:
 *   1. HTTP 200 OK reply within a few seconds.
 *   2. R4 reboots into this sketch within ~10 sec.
 *   3. Serial monitor (after re-opening) shows continuous
 *      HELLO_FROM_OTA_TARGET output every 500 ms.
 *
 * That is the proof that JAndrassy/ArduinoOTA + InternalStorageRenesas works
 * end-to-end on R4 with modem firmware 0.6.0.
 *
 * Recovery: re-flash jandrassy_proto.ino (or the production carduino-v4
 * firmware) over USB.
 */

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 3000) {
    // wait briefly for USB CDC; don't block forever
  }
}

void loop() {
  Serial.println("HELLO_FROM_OTA_TARGET");
  delay(500);
}
