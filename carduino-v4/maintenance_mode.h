// carduino-v4/maintenance_mode.h — state machine for v4.x maintenance/OTA mode.
//
// Real implementation lands in Task 61. This header declares the API the BLE
// dispatcher (Task 58 step 5) and main loop call into; Task 61 replaces the
// stub bodies in maintenance_mode.cpp with the actual MM_NORMAL → MM_ARMED →
// MM_BLE_DRAIN → MM_WIFI_JOINING → MM_OTA_READY → MM_UPLOAD_APPLYING flow.
//
// See V4X-DESIGN.md §5.3 for the state machine spec.

#pragma once

#include <stdint.h>
#include "maintenance_args.h"

// Request the state machine to enter MM_ARMED with the given args.
// Returns false if the machine is already in a non-NORMAL state (busy).
bool maintenance_request_enter(const MaintenanceArgs& args);

// Request abort. Only effective in MM_ARMED (BLE still up). After
// MM_BLE_DRAIN this is a no-op.
void maintenance_request_abort();

// Per-loop tick. The state machine's
// timeouts depend on this being called at least every ~100 ms.
void maintenance_tick();

// True if the machine is in any non-NORMAL state. Other phases of the main
// loop (sensor reads, CAN broadcasts, periodic BLE dumps) gate themselves on
// this being false.
bool maintenance_is_active();
