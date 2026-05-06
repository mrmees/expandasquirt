// expandasquirt-v4/maintenance_mode.cpp — v4.x maintenance/OTA state machine.
//
// State spec in V4X-DESIGN.md §5.3:
//
//     NORMAL ──maintenance cmd──> ARMED ──3s──> BLE_DRAIN ──1s──> WIFI_JOINING
//        ^         │                  │                              │
//        │         │ abort or 10s     │ abort                        │ WL_CONNECTED
//        │         v                  v                              v
//        │      ABORTING <─── (abort during ARMED only) ────       OTA_READY
//        │         │                                                  │
//        │         │ next tick                                        │ onStart cb
//        └─────────┘                                                  v
//                                                              UPLOAD_APPLYING
//                                                                     │
//                                                                     │ apply() resets device
//                                                                     v
//                                                                 (reboot to NORMAL)
//
// Plus per-state timeouts → reboot:
//   - ARMED: 10s hard cap (defense-in-depth past the 3s soft transition)
//   - WIFI_JOINING: 30s deadline
//   - OTA_READY: 5 min idle
//   - OTA_ERROR: 5s
//
// During UPLOAD_APPLYING the JAndrassy library blocks inside ArduinoOTA.poll()
// reading the body, then calls apply() which does NVIC_SystemReset. Control
// never returns to this state machine for a successful upload.

#ifdef ARDUINO

#include "maintenance_mode.h"
#include "can_protocol.h"
#include "ble_console.h"
#include "display_matrix.h"

#include <Arduino.h>
#include <WiFiS3.h>
#include <ArduinoOTA.h>

// Toggle to 1 to re-enable the [mm] state machine traces during bench debugging.
// Spec/use: V4X-DESIGN.md §5.3 maintenance state machine.
#define MM_TRACE 0

#if MM_TRACE
#define MM_TRACE_PRINT(...) do { Serial.print(__VA_ARGS__); } while (0)
#define MM_TRACE_PRINTLN(...) do { Serial.println(__VA_ARGS__); } while (0)
#else
#define MM_TRACE_PRINT(...) do { } while (0)
#define MM_TRACE_PRINTLN(...) do { } while (0)
#endif

namespace {

enum class MMState : uint8_t {
    NORMAL,
    ARMED,
    BLE_DRAIN,
    WIFI_JOINING,
    OTA_READY,
    UPLOAD_APPLYING,
    OTA_ERROR,
    ABORTING,
};

MMState s_state = MMState::NORMAL;
uint32_t s_state_entered_ms = 0;
MaintenanceArgs s_args;

void on_ota_start() {
    // JAndrassy callback: invoked when an upload begins. We just promote the
    // state for observability; the real apply happens inside the library.
    s_state = MMState::UPLOAD_APPLYING;
    s_state_entered_ms = millis();
    display_set_mode(DISP_UPLOADING);
}

void on_ota_error(int /*code*/, const char* /*msg*/) {
    s_state = MMState::OTA_ERROR;
    s_state_entered_ms = millis();
    display_set_mode(DISP_ERROR);
}

void enter_armed() {
    can_send_maintenance_marker();
    display_set_mode(DISP_COUNTDOWN);
    // Periodic dumps stop because the main loop gates BleDumpPhase on
    // !maintenance_is_active(). BLE service stays up so we can still receive
    // a `maintenance abort` command.
}

void enter_wifi_joining() {
    MM_TRACE_PRINTLN(F("[mm] enter_wifi_joining: calling ble_end()"));
    ble_end();
    MM_TRACE_PRINTLN(F("[mm] enter_wifi_joining: ble_end done, calling WiFi.begin"));
    WiFi.begin(s_args.ssid, s_args.psk);
}

void enter_ota_ready() {
    ArduinoOTA.onStart(on_ota_start);
    ArduinoOTA.onError(on_ota_error);
    ArduinoOTA.begin(WiFi.localIP(), "expandasquirt-v4", s_args.ota_pwd, InternalStorage);
    display_set_mode(DISP_AP_READY);
}

void enter_ota_error() {
    display_set_mode(DISP_ERROR);
    WiFi.disconnect();
    WiFi.end();
}

void to_state(MMState s) {
    uint32_t now = millis();
    s_state = s;
    s_state_entered_ms = now;
    MM_TRACE_PRINT(F("[mm] -> "));
    MM_TRACE_PRINT((int)s);
    MM_TRACE_PRINT(F(" at ms="));
    MM_TRACE_PRINTLN(now);
    switch (s) {
        case MMState::ARMED:           enter_armed();         break;
        case MMState::BLE_DRAIN:       /* nothing */          break;
        case MMState::WIFI_JOINING:    enter_wifi_joining();  break;
        case MMState::OTA_READY:       enter_ota_ready();     break;
        case MMState::UPLOAD_APPLYING: /* via on_ota_start */ break;
        case MMState::OTA_ERROR:       enter_ota_error();     break;
        case MMState::ABORTING:        display_set_mode(DISP_NORMAL); break;
        case MMState::NORMAL:          /* only via reboot or ABORTING tick */ break;
    }
}

}  // namespace

bool maintenance_request_enter(const MaintenanceArgs& args) {
    MM_TRACE_PRINT(F("[mm] request_enter: state="));
    MM_TRACE_PRINTLN((int)s_state);
    if (s_state != MMState::NORMAL) return false;
    s_args = args;
    to_state(MMState::ARMED);
    return true;
}

void maintenance_request_abort() {
    MM_TRACE_PRINT(F("[mm] request_abort: state="));
    MM_TRACE_PRINTLN((int)s_state);
    if (s_state == MMState::ARMED) {
        to_state(MMState::ABORTING);
    }
    // After BLE drop (BLE_DRAIN onward), abort is silently ignored — caller
    // would have no way to send the BLE command anyway. Recovery is via the
    // per-state reboot timeouts.
}

bool maintenance_is_active() {
    return s_state != MMState::NORMAL;
}

void maintenance_tick() {
    uint32_t now = millis();
    uint32_t elapsed = now - s_state_entered_ms;

    switch (s_state) {
        case MMState::NORMAL:
            return;

        case MMState::ARMED:
            // 10s hard cap defends against the unlikely case that the 3s
            // transition fails to fire (clock skew, etc.).
            if (elapsed >= 10000) {
                to_state(MMState::ABORTING);
            } else if (elapsed >= 3000) {
                to_state(MMState::BLE_DRAIN);
            }
            return;

        case MMState::BLE_DRAIN:
            if (elapsed >= 1000) to_state(MMState::WIFI_JOINING);
            return;

        case MMState::WIFI_JOINING:
            if (WiFi.status() == WL_CONNECTED && WiFi.localIP() != INADDR_NONE) {
                to_state(MMState::OTA_READY);
            } else if (elapsed >= 30000) {
                NVIC_SystemReset();   // 30s deadline; reboot to recover
            }
            return;

        case MMState::OTA_READY:
            ArduinoOTA.poll();
            if (elapsed >= 5UL * 60 * 1000) {
                NVIC_SystemReset();   // 5 min idle; user changed their mind
            }
            return;

        case MMState::UPLOAD_APPLYING:
            // In practice we won't tick here — JAndrassy's poll() blocks for
            // the whole upload, then calls apply()+NVIC_SystemReset on success.
            // If we DO tick here, an upload completed normally and we're past
            // the apply for some reason — let the next poll handle it.
            ArduinoOTA.poll();
            return;

        case MMState::OTA_ERROR:
            if (elapsed >= 5000) NVIC_SystemReset();
            return;

        case MMState::ABORTING:
            // Single-tick exit to NORMAL. We don't reboot here because BLE is
            // still up and aborting before WIFI_JOINING means we never touched
            // the WiFi/OTA stack — clean to just resume normal operation.
            s_state = MMState::NORMAL;
            return;
    }
}

#else  // !ARDUINO — host-test stub (never compiled by run-tests.sh either, but
       // safe to have if someone adds maintenance_mode.cpp to a host build).

bool maintenance_request_enter(const MaintenanceArgs&) { return false; }
void maintenance_request_abort() {}
void maintenance_tick() {}
bool maintenance_is_active() { return false; }

#endif
