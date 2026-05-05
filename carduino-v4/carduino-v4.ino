#include "config.h"
#include "can_protocol.h"
#include "display_matrix.h"
#include "sensor_pipeline.h"
#include "self_tests.h"
#include "ble_console.h"
#include "persistent.h"

unsigned long lastSensorMs = 0;
unsigned long lastCanMs    = 0;
unsigned long lastBleDumpMs = 0;
unsigned long lastDisplayMs = 0;

bool maintenanceModeActive = false;

static unsigned long last_loop_ms = 0;

static void check_watchdog() {
    unsigned long now = millis();
    unsigned long timeout = maintenanceModeActive ? WATCHDOG_MAINTENANCE_MS : WATCHDOG_NORMAL_MS;
    if (last_loop_ms != 0 && (now - last_loop_ms) > timeout) {
        // RA4M1 + Arduino Renesas core clears RSTSR before main(), so this
        // reboot reads as RESET_UNKNOWN; the boot counter still confirms it.
        Serial.println(F("WATCHDOG TIMEOUT - resetting"));
        delay(50);
        NVIC_SystemReset();
    }
    last_loop_ms = now;
}

void setup() {
    Serial.begin(115200);
    while (!Serial && millis() < 3000) { /* wait briefly for USB */ }

    Serial.println(F("CARDUINO v4 booting..."));

    analogReadResolution(ADC_RESOLUTION_BITS);
    display_init();
    display_set_mode(DISP_BOOT);
    sensor_pipeline_init();

    persistent_init();
    ResetCause cause = read_reset_cause();
    persistent_record_boot(cause);
    Serial.print(F("Boot #")); Serial.print(persistent_get()->boot_counter);
    Serial.print(F(" reset cause=")); Serial.println(cause);

    ErrCode boot_err = run_boot_self_tests();
    if (boot_err == ERR_ADC) {
        Serial.println(F("Halting on ADC failure"));
        display_set_error(1);  // ERR01
        while (1) {
            DisplayUpdate();
            delay(50);
        }
    }
    if (boot_err == ERR_CAN) {
        display_set_error(2);  // ERR02 - degraded mode, do not halt
    }

    if (!ble_init()) {
        display_set_error(3);
        Serial.println(F("BLE init failed - continuing degraded"));
    }

    if (boot_err == ERR_NONE) {
        display_set_mode(DISP_NORMAL);
    }

    // Phases will be initialized as they are added in subsequent tasks
}

void loop() {
    check_watchdog();
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
        if (self_test_can_available()) {
            CanSendPhase();
        }
        lastCanMs = now;
    }

    // CanReceivePhase() — Task 36

    BleServicePhase();

    if (now - lastBleDumpMs >= BLE_PERIOD_MS) {
        BleDumpPhase();
        lastBleDumpMs = now;
    }

    if (now - lastDisplayMs >= DISPLAY_PERIOD_MS) {
        DisplayUpdate();
        lastDisplayMs = now;
    }

    if (maintenanceModeActive) {
        // HttpServerServicePhase() — Task 42
    }
}
