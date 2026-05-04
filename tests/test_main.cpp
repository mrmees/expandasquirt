// tests/test_main.cpp â€” entry point for host-side tests.
// Each new test file gets #included here.

#include "test_helpers.h"

// Sanity test to verify the harness itself works
TEST_CASE(harness_sanity) {
    ASSERT_EQ(1 + 1, 2);
    ASSERT_NEAR(3.14159, 3.14, 0.01);
}

// Future: include test files for each module
// #include "test_sensor_pipeline.cpp"
// #include "test_can_protocol.cpp"
// #include "test_sensor_health.cpp"

int main() {
    printf("Tests complete: %d passed, %d failed\n", g_test_passed, g_test_failed);
    return g_test_failed > 0 ? 1 : 0;
}
