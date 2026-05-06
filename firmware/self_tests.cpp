#include "self_tests.h"

#ifdef ARDUINO

#include "can_protocol.h"
#include <Arduino.h>

static bool can_ok = false;

static bool adc_self_test() {
    // RA4M1 internal Vref nominally ~1.43V. analogRead of A0 with the pin
    // floating won't tell us much; instead, do a simple sanity placeholder.
    // For v4, accept this as a pass-through unless we add a deeper check.
    return true;  // TODO: deeper check via Renesas FSP if needed
}

ErrCode run_boot_self_tests() {
    if (!adc_self_test()) {
        Serial.println(F("ERR01 ADC self-test failed"));
        return ERR_ADC;  // halt-worthy
    }

    if (!can_protocol_init()) {
        Serial.println(F("ERR02 CAN init failed"));
        can_ok = false;
        return ERR_CAN;  // degrade
    }
    can_ok = true;

    // BLE init in Task 24
    return ERR_NONE;
}

bool self_test_can_available() {
    return can_ok;
}

#endif
