// tests/test_pct_decode.cpp — host tests for the percent-encoding helper used
// by the maintenance command parser (V4X-DESIGN.md §5.1, IMPLEMENTATION-PLAN.md
// Task 57). Included by test_main.cpp.

#include "../carduino-v4/pct_decode.h"

TEST_CASE(pct_decode_plain_ascii_passthrough) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("hello", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)5);
    ASSERT_TRUE(std::memcmp(out, "hello", 5) == 0);
}

TEST_CASE(pct_decode_empty_string) {
    char out[64];
    size_t n = 99;
    ASSERT_TRUE(pct_decode("", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)0);
}

TEST_CASE(pct_decode_single_space_escape) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("a%20b", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)3);
    ASSERT_TRUE(std::memcmp(out, "a b", 3) == 0);
}

TEST_CASE(pct_decode_multiple_escapes) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("%21%40%23", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)3);
    ASSERT_TRUE(std::memcmp(out, "!@#", 3) == 0);
}

TEST_CASE(pct_decode_lowercase_hex) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("%2a%7e", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)2);
    ASSERT_TRUE(std::memcmp(out, "*~", 2) == 0);
}

TEST_CASE(pct_decode_mixed_case_hex) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("%aB%cD", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)2);
    ASSERT_EQ((unsigned char)out[0], 0xAB);
    ASSERT_EQ((unsigned char)out[1], 0xCD);
}

TEST_CASE(pct_decode_high_bytes) {
    // WPA2 PSKs can contain non-ASCII when encoded; verify high bytes round-trip.
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("%C2%A0", out, sizeof(out), &n));  // U+00A0 NBSP
    ASSERT_EQ(n, (size_t)2);
    ASSERT_EQ((unsigned char)out[0], 0xC2);
    ASSERT_EQ((unsigned char)out[1], 0xA0);
}

TEST_CASE(pct_decode_rejects_non_hex_digit_high) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(!pct_decode("a%2Gb", out, sizeof(out), &n));
}

TEST_CASE(pct_decode_rejects_non_hex_digit_low) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(!pct_decode("a%G2b", out, sizeof(out), &n));
}

TEST_CASE(pct_decode_rejects_incomplete_escape_at_end) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(!pct_decode("foo%2", out, sizeof(out), &n));
}

TEST_CASE(pct_decode_rejects_lone_percent_at_end) {
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(!pct_decode("foo%", out, sizeof(out), &n));
}

TEST_CASE(pct_decode_rejects_overflow) {
    // Output capacity 3 can hold "lon" but not "longer"
    char small[3];
    size_t n = 0;
    ASSERT_TRUE(!pct_decode("longer", small, sizeof(small), &n));
}

TEST_CASE(pct_decode_exact_capacity_fits) {
    // "abc" is exactly 3 bytes, fits a 3-byte output (no null terminator written)
    char out[3];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("abc", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)3);
    ASSERT_TRUE(std::memcmp(out, "abc", 3) == 0);
}

TEST_CASE(pct_decode_passes_full_typical_ssid) {
    // Realistic phone hotspot SSID with a space
    char out[64];
    size_t n = 0;
    ASSERT_TRUE(pct_decode("My%20Phone%20Hotspot", out, sizeof(out), &n));
    ASSERT_EQ(n, (size_t)16);
    ASSERT_TRUE(std::memcmp(out, "My Phone Hotspot", 16) == 0);
}
