// prototypes/ota_proto/ota_proto.ino
//
// Task 38 prototype — answer the load-bearing question for our OTA design:
// "Can the host MCU stream chunked bytes onto the modem filesystem?"
//
// Diagnostic test plan:
//   A) Single WRITE                                  (baseline)
//   B) WRITE-then-WRITE on same file with 200ms gap  (truncating reuse)
//   C) WRITE-then-APPEND with 200ms gap              (the canonical chunked path)
//   D) WRITE + 5 APPENDs with 500ms gap              (stress)
//   E) WRITE + 5 APPENDs with 50ms gap               (timing sensitivity)
// After each scenario, report writefile() return values so we can see exactly
// which call breaks. Re-runs every 30 s in loop() so capture is easy.

#include <WiFiS3.h>
#include <WiFiFileSystem.h>

WiFiFileSystem fs;

static const char* TEST_FILE = "/proto_test.bin";
static bool modem_ready = false;

static void hr(const char* label) {
    Serial.print(F("--- "));
    Serial.print(label);
    Serial.println(F(" ---"));
}

// Returns the file size implied by sum of successful writes.
// Each call prints "<op> chunk <i>: returned <n>".
static size_t do_write(int op, char fill, size_t size, int idx) {
    char* buf = (char*)malloc(size);
    if (!buf) { Serial.println(F("  malloc fail")); return 0; }
    for (size_t i = 0; i < size; i++) buf[i] = fill;
    size_t got = fs.writefile(TEST_FILE, buf, size, op);
    free(buf);
    Serial.print(F("  "));
    Serial.print(op == WIFI_FILE_WRITE ? "WRITE  " : "APPEND ");
    Serial.print(F("chunk "));
    Serial.print(idx);
    Serial.print(F(" size="));
    Serial.print(size);
    Serial.print(F(" returned="));
    Serial.println(got);
    return got;
}

static void run_tests() {
    Serial.println(F("\n========== OTA Proto cycle =========="));

    if (!modem_ready) {
        Serial.print(F("WiFi.beginAP() ... "));
        uint8_t apr = WiFi.beginAP("CARDUINO-PROTO-FS", "protopass1234");
        Serial.println(apr);  // expect 7 = WL_AP_LISTENING
        delay(1500);

        Serial.print(F("Firmware version: "));
        Serial.println(WiFi.firmwareVersion());

        fs.mount(true);
        Serial.println(F("fs.mount(true) returned"));
        delay(500);
        modem_ready = true;
    }

    hr("Test A: single WRITE");
    do_write(WIFI_FILE_WRITE, 'a', 25, 0);
    delay(300);

    hr("Test B: WRITE x2 (truncating reuse)");
    do_write(WIFI_FILE_WRITE, 'b', 25, 0);
    delay(200);
    do_write(WIFI_FILE_WRITE, 'B', 25, 1);
    delay(300);

    hr("Test C: WRITE then APPEND once");
    do_write(WIFI_FILE_WRITE,  'c', 64, 0);
    delay(200);
    do_write(WIFI_FILE_APPEND, 'C', 64, 1);
    delay(300);

    hr("Test D: WRITE + 5x APPEND, 500ms gap");
    do_write(WIFI_FILE_WRITE, 'd', 64, 0);
    delay(500);
    for (int i = 1; i <= 5; i++) {
        do_write(WIFI_FILE_APPEND, '0' + i, 64, i);
        delay(500);
    }

    hr("Test E: WRITE + 5x APPEND, 50ms gap");
    do_write(WIFI_FILE_WRITE, 'e', 64, 0);
    delay(50);
    for (int i = 1; i <= 5; i++) {
        do_write(WIFI_FILE_APPEND, '0' + i, 64, i);
        delay(50);
    }

    Serial.println(F("========== Cycle done. Re-run in 20s. =========="));
}

void setup() {
    Serial.begin(115200);
    while (!Serial && millis() < 3000) {}
    delay(2000);
}

void loop() {
    run_tests();
    delay(20000);
}
