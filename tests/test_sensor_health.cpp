// tests/test_sensor_health.cpp

#include "config.h"

extern "C" {
bool electrical_fault(int adc_raw);
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
