#include "config.h"
#include "sensor_pipeline.h"

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
    sensor_pipeline_init();

    // Phases will be initialized as they are added in subsequent tasks
}

void loop() {
    unsigned long now = millis();

    if (now - lastSensorMs >= SENSOR_PERIOD_MS) {
        SensorPhase();
        // Temporary: print sensor readings to USB serial every 500 ms
        static unsigned long lastPrintMs = 0;
        if (now - lastPrintMs >= 500) {
            Serial.print(F("oilT=")); Serial.print(gSensorState.oil_temp_F_x10 / 10.0f, 1);
            Serial.print(F(" oilP=")); Serial.print(gSensorState.oil_pressure_psi_x10 / 10.0f, 1);
            Serial.print(F(" fuelP=")); Serial.print(gSensorState.fuel_pressure_psi_x10 / 10.0f, 1);
            Serial.print(F(" preP=")); Serial.print(gSensorState.pre_sc_pressure_kpa_x10 / 10.0f, 1);
            Serial.print(F(" postT=")); Serial.println(gSensorState.post_sc_temp_F_x10 / 10.0f, 1);
            lastPrintMs = now;
        }
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
