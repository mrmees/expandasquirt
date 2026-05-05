// carduino-v4/maintenance_mode.cpp — STUB. Real state machine lands in Task 61.
//
// These stubs let Task 58's BLE dispatcher wiring compile and link. Until
// Task 61 lands, `maintenance` BLE commands will return `ERR maintenance busy`
// (because request_enter() returns false). That's harmless during v4 testing
// — we're not exercising maintenance flows until v4.x is fully built.

#include "maintenance_mode.h"

bool maintenance_request_enter(const MaintenanceArgs& /*args*/) {
    // Task 61 will implement the real state machine here.
    return false;
}

void maintenance_request_abort() {
    // Task 61.
}

void maintenance_tick(uint32_t /*now_ms*/) {
    // Task 61.
}

bool maintenance_is_active() {
    return false;
}
