#include "maintenance_args.h"
#include "pct_decode.h"

#include <string.h>

namespace {

// Local field-set bitmask. We require all three fields to be present, and we
// reject duplicate keys (which would otherwise silently overwrite).
constexpr uint8_t FIELD_SSID = 1 << 0;
constexpr uint8_t FIELD_PSK  = 1 << 1;
constexpr uint8_t FIELD_PWD  = 1 << 2;
constexpr uint8_t ALL_FIELDS = FIELD_SSID | FIELD_PSK | FIELD_PWD;

// Skip ASCII whitespace (just space — the wire format only allows spaces).
inline const char* skip_spaces(const char* p) {
    while (*p == ' ') ++p;
    return p;
}

}  // namespace

MaintenanceParseResult maintenance_parse_args(const char* line, MaintenanceArgs& out) {
    out.ssid[0] = 0;
    out.psk[0] = 0;
    out.ota_pwd[0] = 0;

    uint8_t fields_seen = 0;
    const char* p = skip_spaces(line);
    if (*p == 0) return MaintenanceParseResult::BAD_ARGS;

    while (*p) {
        // Find '=' for the current key
        const char* eq = strchr(p, '=');
        if (!eq) return MaintenanceParseResult::BAD_ARGS;

        size_t key_len = (size_t)(eq - p);
        if (key_len == 0) return MaintenanceParseResult::BAD_ARGS;

        // Find end of value (next space or end-of-string)
        const char* val = eq + 1;
        const char* val_end = val;
        while (*val_end && *val_end != ' ') ++val_end;
        size_t val_len = (size_t)(val_end - val);
        if (val_len == 0) return MaintenanceParseResult::BAD_ARGS;

        // Identify destination by key
        char* dest = nullptr;
        size_t dest_cap = 0;
        uint8_t bit = 0;

        if (key_len == 4 && memcmp(p, "ssid", 4) == 0) {
            dest = out.ssid; dest_cap = sizeof(out.ssid); bit = FIELD_SSID;
        } else if (key_len == 3 && memcmp(p, "psk", 3) == 0) {
            dest = out.psk;  dest_cap = sizeof(out.psk);  bit = FIELD_PSK;
        } else if (key_len == 3 && memcmp(p, "pwd", 3) == 0) {
            dest = out.ota_pwd; dest_cap = sizeof(out.ota_pwd); bit = FIELD_PWD;
        } else {
            return MaintenanceParseResult::BAD_ARGS;  // unknown key
        }

        // Reject duplicate keys
        if (fields_seen & bit) return MaintenanceParseResult::BAD_ARGS;

        // Encoded value can't itself exceed dest buffer (decoded is always <=
        // encoded length, so we check encoded as a fast rejection); we still
        // re-check post-decode since a percent-decode shrinks length.
        if (val_len >= dest_cap) {
            // Could be ARG_TOO_LONG even if decoded length would fit. Conservative:
            // if even the encoded form doesn't fit and the encoded form contains
            // no escapes, then the decoded form also won't fit.
            // For correctness, check if there's any '%' in the value.
            bool has_pct = false;
            for (size_t i = 0; i < val_len; ++i) {
                if (val[i] == '%') { has_pct = true; break; }
            }
            if (!has_pct) return MaintenanceParseResult::ARG_TOO_LONG;
        }

        // Copy encoded value to a stack buffer for null-termination, then decode.
        // Use a generous local buffer; production messages are well under 200 bytes.
        char enc[200];
        if (val_len >= sizeof(enc)) return MaintenanceParseResult::ARG_TOO_LONG;
        memcpy(enc, val, val_len);
        enc[val_len] = 0;

        size_t decoded_len = 0;
        if (!pct_decode(enc, dest, dest_cap - 1, &decoded_len)) {
            // Distinguish overflow from malformed by trying again with a larger temp buffer
            char tmp[200];
            size_t tmp_len = 0;
            if (pct_decode(enc, tmp, sizeof(tmp), &tmp_len)) {
                // Decoded successfully into tmp but didn't fit dest — too long
                return MaintenanceParseResult::ARG_TOO_LONG;
            }
            return MaintenanceParseResult::BAD_ARGS;
        }
        if (decoded_len == 0) return MaintenanceParseResult::BAD_ARGS;
        dest[decoded_len] = 0;

        fields_seen |= bit;

        p = skip_spaces(val_end);
    }

    if (fields_seen != ALL_FIELDS) return MaintenanceParseResult::BAD_ARGS;
    return MaintenanceParseResult::OK;
}
