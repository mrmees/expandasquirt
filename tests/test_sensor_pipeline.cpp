// tests/test_sensor_pipeline.cpp

extern "C" {
// Mirror declarations from sensor_pipeline.h that we want to test
float thermistor_to_F(int adc_raw, float pullup_ohms, float r25, float beta);
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
