// expandasquirt-v4/util/pct_decode.h — percent-encoding decoder for the maintenance
// command parser (V4X-DESIGN.md §5.1). Used to safely carry SSID / WPA2 PSK /
// OTA password values through the existing single-space BLE command parser
// without escaping headaches.
//
// Pure C-style API so it can be unit-tested with the project's host-side
// g++ harness without dragging in Arduino headers.

#pragma once

#include <stddef.h>

// Decode a percent-encoded ASCII string into a fixed-capacity output buffer.
//
// `in`        null-terminated input
// `out`       destination buffer (NOT null-terminated by this function — caller
//             must terminate with `out[*out_len] = 0` if needed and budget room)
// `out_cap`   capacity of `out` in bytes; decoded length must be <= out_cap
// `out_len`   on success, set to the number of decoded bytes written to `out`
//
// Returns true on success, false on:
//   - malformed escape (`%` not followed by two hex digits)
//   - truncated escape at end of input
//   - decoded length exceeds out_cap
bool pct_decode(const char* in, char* out, size_t out_cap, size_t* out_len);
