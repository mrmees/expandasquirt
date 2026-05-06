// tests/test_maintenance_args.cpp — host tests for the maintenance-command
// argument parser (V4X-DESIGN.md §5.1, IMPLEMENTATION-PLAN.md Task 58).
// Included by test_main.cpp.

#include "../firmware/maintenance_args.h"

TEST_CASE(maint_args_happy_path) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=MEES psk=hunter2 pwd=otapwx", args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_TRUE(std::strcmp(args.ssid, "MEES") == 0);
    ASSERT_TRUE(std::strcmp(args.psk, "hunter2") == 0);
    ASSERT_TRUE(std::strcmp(args.ota_pwd, "otapwx") == 0);
}

TEST_CASE(maint_args_keys_in_any_order) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("pwd=a psk=b ssid=c", args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_TRUE(std::strcmp(args.ssid, "c") == 0);
    ASSERT_TRUE(std::strcmp(args.psk, "b") == 0);
    ASSERT_TRUE(std::strcmp(args.ota_pwd, "a") == 0);
}

TEST_CASE(maint_args_pct_encoded_ssid_with_space) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=My%20Phone psk=p1 pwd=p2", args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_TRUE(std::strcmp(args.ssid, "My Phone") == 0);
    ASSERT_TRUE(std::strcmp(args.psk, "p1") == 0);
    ASSERT_TRUE(std::strcmp(args.ota_pwd, "p2") == 0);
}

TEST_CASE(maint_args_pct_encoded_psk_with_special_chars) {
    MaintenanceArgs args;
    // PSK with %, =, space — all encoded
    auto r = maintenance_parse_args("ssid=net psk=p%25%3D%20w pwd=otpw", args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    int cmp = std::strcmp(args.psk, "p%= w");
    ASSERT_EQ(cmp, 0);
}

TEST_CASE(maint_args_rejects_missing_pwd) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=net psk=p", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_missing_ssid) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("psk=p pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_missing_psk) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=net pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_empty_ssid_value) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid= psk=p pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_empty_psk_value) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=n psk= pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_empty_pwd_value) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=n psk=p pwd=", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_malformed_pct_escape) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=a%2G psk=p pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_unknown_key) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=n psk=p pwd=q wat=huh", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_no_equals_sign) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=n psk=p justatoken pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_ssid_too_long) {
    MaintenanceArgs args;
    // ssid buffer holds 32 chars + null. 33+ chars should fail.
    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "ssid=%s psk=p pwd=q",
                  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");  // 33 A's
    auto r = maintenance_parse_args(buf, args);
    ASSERT_TRUE(r == MaintenanceParseResult::ARG_TOO_LONG);
}

TEST_CASE(maint_args_accepts_ssid_at_max_length) {
    MaintenanceArgs args;
    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "ssid=%s psk=p pwd=q",
                  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");  // 32 A's = max
    auto r = maintenance_parse_args(buf, args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_EQ(std::strlen(args.ssid), (size_t)32);
}

TEST_CASE(maint_args_rejects_psk_too_long) {
    MaintenanceArgs args;
    char buf[256];
    char long_psk[65];
    std::memset(long_psk, 'p', 64);
    long_psk[64] = 0;
    std::snprintf(buf, sizeof(buf), "ssid=n psk=%s pwd=q", long_psk);
    auto r = maintenance_parse_args(buf, args);
    ASSERT_TRUE(r == MaintenanceParseResult::ARG_TOO_LONG);
}

TEST_CASE(maint_args_accepts_psk_at_wpa2_max) {
    MaintenanceArgs args;
    char buf[256];
    char psk_at_max[64];
    std::memset(psk_at_max, 'p', 63);
    psk_at_max[63] = 0;
    std::snprintf(buf, sizeof(buf), "ssid=n psk=%s pwd=q", psk_at_max);
    auto r = maintenance_parse_args(buf, args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_EQ(std::strlen(args.psk), (size_t)63);
}

TEST_CASE(maint_args_rejects_pwd_too_long) {
    MaintenanceArgs args;
    char buf[256];
    char long_pwd[40];
    std::memset(long_pwd, 'p', 39);
    long_pwd[39] = 0;
    std::snprintf(buf, sizeof(buf), "ssid=n psk=p pwd=%s", long_pwd);
    auto r = maintenance_parse_args(buf, args);
    ASSERT_TRUE(r == MaintenanceParseResult::ARG_TOO_LONG);
}

TEST_CASE(maint_args_handles_extra_spaces) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("ssid=n  psk=p   pwd=q", args);
    // Per V4X-DESIGN.md §6.7 the existing parser is single-space separated;
    // arguments themselves must not contain spaces. We accept multiple spaces
    // between key=value pairs as a small leniency.
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
    ASSERT_TRUE(std::strcmp(args.ssid, "n") == 0);
    ASSERT_TRUE(std::strcmp(args.psk, "p") == 0);
    ASSERT_TRUE(std::strcmp(args.ota_pwd, "q") == 0);
}

TEST_CASE(maint_args_handles_leading_trailing_spaces) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("  ssid=n psk=p pwd=q  ", args);
    ASSERT_TRUE(r == MaintenanceParseResult::OK);
}

TEST_CASE(maint_args_rejects_empty_line) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_only_spaces) {
    MaintenanceArgs args;
    auto r = maintenance_parse_args("   ", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}

TEST_CASE(maint_args_rejects_duplicate_key) {
    MaintenanceArgs args;
    // Per the design, this should be rejected — args appearing twice is a
    // clear protocol violation, even though picking last-wins or first-wins
    // would also be defensible. Reject for simplicity.
    auto r = maintenance_parse_args("ssid=a ssid=b psk=p pwd=q", args);
    ASSERT_TRUE(r == MaintenanceParseResult::BAD_ARGS);
}
