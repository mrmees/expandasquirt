// tests/test_sensor_health.cpp

#include "config.h"
#include "sensor_health.h"

extern "C" {
bool electrical_fault(int adc_raw);
void debounce_init(DebounceState* d);
bool debounce_update(DebounceState* d, bool sample_bad);
}

TEST_CASE(electrical_fault_at_zero) {
    ASSERT_TRUE(electrical_fault(0));
}

TEST_CASE(electrical_fault_at_full) {
    ASSERT_TRUE(electrical_fault(ADC_MAX_COUNT));
}

TEST_CASE(electrical_fault_at_low_threshold) {
    // 1% of 16383 ~= 163; below that = fault, above = OK
    ASSERT_TRUE(electrical_fault(150));
    ASSERT_TRUE(!electrical_fault(200));
}

TEST_CASE(electrical_fault_at_high_threshold) {
    // 99% of 16383 ~= 16219
    ASSERT_TRUE(electrical_fault(16300));
    ASSERT_TRUE(!electrical_fault(16000));
}

TEST_CASE(electrical_fault_in_normal_range) {
    ASSERT_TRUE(!electrical_fault(8192));
    ASSERT_TRUE(!electrical_fault(2000));
    ASSERT_TRUE(!electrical_fault(14000));
}

TEST_CASE(debounce_starts_clear) {
    DebounceState d;
    debounce_init(&d);
    ASSERT_TRUE(!debounce_update(&d, false));
}

TEST_CASE(debounce_asserts_after_HEALTH_DEBOUNCE_BAD) {
    DebounceState d;
    debounce_init(&d);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD - 1; i++) {
        ASSERT_TRUE(!debounce_update(&d, true));
    }
    ASSERT_TRUE(debounce_update(&d, true));
    ASSERT_TRUE(debounce_update(&d, true));
}

TEST_CASE(debounce_clears_after_HEALTH_DEBOUNCE_GOOD) {
    DebounceState d;
    debounce_init(&d);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD; i++) debounce_update(&d, true);
    for (int i = 0; i < HEALTH_DEBOUNCE_GOOD - 1; i++) {
        ASSERT_TRUE(debounce_update(&d, false));
    }
    ASSERT_TRUE(!debounce_update(&d, false));
}

TEST_CASE(debounce_intermittent_bad_does_not_clear) {
    DebounceState d;
    debounce_init(&d);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD; i++) debounce_update(&d, true);
    for (int i = 0; i < HEALTH_DEBOUNCE_GOOD - 2; i++) debounce_update(&d, false);
    debounce_update(&d, true);
    ASSERT_TRUE(debounce_update(&d, false));
}
