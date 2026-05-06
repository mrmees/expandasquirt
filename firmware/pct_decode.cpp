#include "pct_decode.h"

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool pct_decode(const char* in, char* out, size_t out_cap, size_t* out_len) {
    size_t i = 0;
    size_t j = 0;
    while (in[i]) {
        if (j >= out_cap) return false;

        if (in[i] == '%') {
            // Need two hex digits following
            if (in[i + 1] == 0 || in[i + 2] == 0) return false;
            int hi = hex_nibble(in[i + 1]);
            int lo = hex_nibble(in[i + 2]);
            if (hi < 0 || lo < 0) return false;
            out[j++] = (char)((hi << 4) | lo);
            i += 3;
        } else {
            out[j++] = in[i++];
        }
    }

    *out_len = j;
    return true;
}
