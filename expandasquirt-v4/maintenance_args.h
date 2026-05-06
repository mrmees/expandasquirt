// expandasquirt-v4/maintenance_args.h — parser for the BLE `maintenance ssid=…
// psk=… pwd=…` command. Pure logic, host-testable. The state machine that
// consumes parsed args lives in maintenance_mode.{h,cpp} (Task 61).
//
// See V4X-DESIGN.md §5.1 for the wire format.

#pragma once

#include <stddef.h>
#include <stdint.h>

// SSID / PSK / OTA password buffers sized to standards-imposed maxima + 1 for
// null terminator. The plan calls for these limits in V4X-DESIGN.md §5.1.
struct MaintenanceArgs {
    char ssid[33];     // <= 32 bytes (Wi-Fi standard)
    char psk[64];      // <= 63 bytes (WPA2 PSK max)
    char ota_pwd[33];  // <= 32 bytes (project-imposed)
};

enum class MaintenanceParseResult : uint8_t {
    OK,
    BAD_ARGS,        // missing/malformed key=value pairs, empty values, malformed pct-escapes, unknown keys
    ARG_TOO_LONG,    // a value exceeds its dest buffer
};

// Parse a "ssid=<pct> psk=<pct> pwd=<pct>" line (single-space separated, in any
// order) into `out`. The leading "maintenance " keyword must already have been
// stripped by the caller. The line must NOT contain a trailing newline.
//
// On OK, all three of out.ssid, out.psk, out.ota_pwd are non-empty
// null-terminated strings.
//
// On any error, the contents of `out` are unspecified.
MaintenanceParseResult maintenance_parse_args(const char* line, MaintenanceArgs& out);
