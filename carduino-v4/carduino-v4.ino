#include "config.h"

unsigned long lastSensorMs = 0;
unsigned long lastCanMs    = 0;
unsigned long lastBleDumpMs = 0;
unsigned long lastDisplayMs = 0;

bool maintenanceModeActive = false;

void setup() {
    Serial.begin(115200);
    while (!Serial && millis() < 3000) { /* wait briefly for USB */ }

    Serial.println(F("CARDUINO v4 booting..."));

    analogReadResolution(ADC_RESOLUTION_BITS);

    // Phases will be initialized as they are added in subsequent tasks
}

void loop() {
    unsigned long now = millis();

    if (now - lastSensorMs >= SENSOR_PERIOD_MS) {
        // SensorPhase() — Task 9
        lastSensorMs = now;
    }

    if (now - lastCanMs >= CAN_PERIOD_MS) {
        // CanSendPhase() — Task 13
        lastCanMs = now;
    }

    // CanReceivePhase() — Task 36

    // BleServicePhase() — Task 24

    if (now - lastBleDumpMs >= BLE_PERIOD_MS) {
        // BleDumpPhase() — Task 26
        lastBleDumpMs = now;
    }

    if (now - lastDisplayMs >= DISPLAY_PERIOD_MS) {
        // DisplayUpdate() — Task 23
        lastDisplayMs = now;
    }

    if (maintenanceModeActive) {
        // HttpServerServicePhase() — Task 42
    }
}
