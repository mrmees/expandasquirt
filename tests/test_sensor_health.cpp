// tests/test_sensor_health.cpp

#include "config.h"
#include "sensor_health.h"

extern "C" {
bool electrical_fault(int adc_raw);
void debounce_init(DebounceState* d);
bool debounce_update(DebounceState* d, bool sample_bad);
void channel_health_init(ChannelHealth* ch);
bool channel_health_update(ChannelHealth* ch, int raw_adc, float eng_value,
                           bool (*plausibility_fn)(float));
bool plausibility_oil_temp_F(float v);
bool plausibility_pressure_psi(float v);
bool plausibility_kpa(float v);
}

static bool always_plausible(float v) { (void)v; return true; }
static bool never_plausible(float v)  { (void)v; return false; }

static float last_plausibility_value = 0.0f;
static bool recording_plausibility(float v) {
    last_plausibility_value = v;
    return v >= 10.0f && v <= 20.0f;
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

TEST_CASE(plausibility_oil_temp_in_range) {
    ASSERT_TRUE(plausibility_oil_temp_F(180.0f));
    ASSERT_TRUE(plausibility_oil_temp_F(-30.0f));
    ASSERT_TRUE(!plausibility_oil_temp_F(-100.0f));
    ASSERT_TRUE(!plausibility_oil_temp_F(500.0f));
}

TEST_CASE(plausibility_pressure_in_range) {
    ASSERT_TRUE(plausibility_pressure_psi(50.0f));
    ASSERT_TRUE(!plausibility_pressure_psi(-10.0f));
    ASSERT_TRUE(!plausibility_pressure_psi(250.0f));
}

TEST_CASE(plausibility_kpa_in_range) {
    ASSERT_TRUE(plausibility_kpa(101.0f));
    ASSERT_TRUE(!plausibility_kpa(-5.0f));
    ASSERT_TRUE(!plausibility_kpa(300.0f));
}

TEST_CASE(channel_health_starts_healthy) {
    ChannelHealth ch;
    channel_health_init(&ch);
    ASSERT_TRUE(channel_health_update(&ch, 8192, 42.0f, always_plausible));
    ASSERT_EQ(0, ch.age_ticks);
}

TEST_CASE(channel_health_electrical_fault_asserts_after_HEALTH_DEBOUNCE_BAD) {
    ChannelHealth ch;
    channel_health_init(&ch);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD - 1; i++) {
        ASSERT_TRUE(channel_health_update(&ch, 0, 42.0f, always_plausible));
    }
    ASSERT_TRUE(!channel_health_update(&ch, 0, 42.0f, always_plausible));
}

TEST_CASE(channel_health_clears_after_HEALTH_DEBOUNCE_GOOD) {
    ChannelHealth ch;
    channel_health_init(&ch);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD; i++) {
        channel_health_update(&ch, 0, 42.0f, always_plausible);
    }
    for (int i = 0; i < HEALTH_DEBOUNCE_GOOD - 1; i++) {
        ASSERT_TRUE(!channel_health_update(&ch, 8192, 42.0f, always_plausible));
    }
    ASSERT_TRUE(channel_health_update(&ch, 8192, 42.0f, always_plausible));
}

TEST_CASE(channel_health_age_increments_at_10hz) {
    ChannelHealth ch;
    channel_health_init(&ch);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD; i++) {
        channel_health_update(&ch, 0, 42.0f, always_plausible);
    }
    ch.age_ticks = 0;
    ch.tick_subdiv = 0;
    for (int i = 0; i < 30; i++) {
        ASSERT_TRUE(!channel_health_update(&ch, 0, 42.0f, always_plausible));
    }
    ASSERT_EQ(3, ch.age_ticks);
}

TEST_CASE(channel_health_age_resets_on_healthy) {
    ChannelHealth ch;
    channel_health_init(&ch);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD + 20; i++) {
        channel_health_update(&ch, 0, 42.0f, always_plausible);
    }
    ASSERT_TRUE(ch.age_ticks > 0);
    for (int i = 0; i < HEALTH_DEBOUNCE_GOOD - 1; i++) {
        ASSERT_TRUE(!channel_health_update(&ch, 8192, 42.0f, always_plausible));
    }
    ASSERT_TRUE(channel_health_update(&ch, 8192, 42.0f, always_plausible));
    ASSERT_EQ(0, ch.age_ticks);
}

TEST_CASE(channel_health_plausibility_uses_eng_value) {
    ChannelHealth ch;
    channel_health_init(&ch);
    last_plausibility_value = 0.0f;
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD - 1; i++) {
        ASSERT_TRUE(channel_health_update(&ch, 8192, 99.5f, recording_plausibility));
    }
    ASSERT_EQ(99, (int)last_plausibility_value);
    ASSERT_TRUE(!channel_health_update(&ch, 8192, 99.5f, recording_plausibility));

    ChannelHealth ch2;
    channel_health_init(&ch2);
    for (int i = 0; i < HEALTH_DEBOUNCE_BAD - 1; i++) {
        ASSERT_TRUE(channel_health_update(&ch2, 8192, 42.0f, never_plausible));
    }
    ASSERT_TRUE(!channel_health_update(&ch2, 8192, 42.0f, never_plausible));
}
