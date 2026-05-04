// tests/test_sensor_pipeline.cpp

#include "config.h"

extern "C" {
// Mirror declarations from sensor_pipeline.h that we want to test
float thermistor_to_F(int adc_raw, float pullup_ohms, float r25, float beta);
float pressure_psi(int adc_raw, float psi_at_full_scale);
float bosch_kpa(int adc_raw);
}

TEST_CASE(thermistor_at_25C_returns_77F) {
    // At 25C, NTC resistance equals R25. With equal pull-up,
    // ADC should read mid-scale. 14-bit half-scale = 8192.
    float result = thermistor_to_F(8192, 10000.0f, 10000.0f, 3950.0f);
    ASSERT_NEAR(result, 77.0f, 1.0f);  // 25C = 77F
}

TEST_CASE(thermistor_at_extremes) {
    // Pull-up topology (per sensor_pipeline.h): pull-up between V+ and ADC,
    // thermistor between ADC and GND. So high ADC = high thermistor R = cold,
    // low ADC = low thermistor R = hot.

    // Near zero ADC -> very low thermistor resistance -> very hot
    float hot = thermistor_to_F(500, 10000.0f, 10000.0f, 3950.0f);
    ASSERT_TRUE(hot > 200.0f);

    // Near full-scale ADC -> very high thermistor resistance -> very cold
    float cold = thermistor_to_F(15000, 10000.0f, 10000.0f, 3950.0f);
    ASSERT_TRUE(cold < 0.0f);
}

TEST_CASE(pressure_psi_zero) {
    ASSERT_NEAR(pressure_psi(0, 100.0f), 0.0f, 0.01f);
}

TEST_CASE(pressure_psi_half_scale) {
    ASSERT_NEAR(pressure_psi(ADC_MAX_COUNT / 2, 100.0f), 50.0f, 0.5f);
}

TEST_CASE(pressure_psi_full_scale) {
    ASSERT_NEAR(pressure_psi(ADC_MAX_COUNT, 100.0f), 100.0f, 0.5f);
}

TEST_CASE(pressure_psi_different_full_scales) {
    ASSERT_NEAR(pressure_psi(ADC_MAX_COUNT / 2, 150.0f), 75.0f, 0.5f);
    ASSERT_NEAR(pressure_psi(ADC_MAX_COUNT / 4, 200.0f), 50.0f, 0.5f);
}

TEST_CASE(bosch_kpa_at_4V_input) {
    // 4V -> 4/5 of ADC_MAX_COUNT
    int adc = (int)(0.8f * ADC_MAX_COUNT);
    float expected = 24.7f * 4.0f + 0.12f;  // = 98.92 kPa
    ASSERT_NEAR(bosch_kpa(adc), expected, 1.0f);
}

TEST_CASE(bosch_kpa_at_atmospheric) {
    // ~101 kPa typical sea-level, sensor outputs ~4.08V
    // V = (kPa - offset) / slope = (101 - 0.12) / 24.7 ~= 4.08V
    int adc = (int)((4.08f / V_REF) * ADC_MAX_COUNT);
    ASSERT_NEAR(bosch_kpa(adc), 101.0f, 2.0f);
}

TEST_CASE(bosch_kpa_at_zero_input) {
    // 0V -> just the offset
    ASSERT_NEAR(bosch_kpa(0), BOSCH_OFFSET, 0.01f);
}
