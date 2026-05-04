#include "sensor_pipeline.h"
#include "sensor_health.h"
#include <math.h>

float thermistor_to_F(int adc_raw, float pullup_ohms, float r25, float beta) {
    if (adc_raw <= 0)             adc_raw = 1;
    if (adc_raw >= ADC_MAX_COUNT) adc_raw = ADC_MAX_COUNT - 1;

    float ratio = (float)adc_raw / (float)(ADC_MAX_COUNT + 1);
    float r_therm = pullup_ohms * ratio / (1.0f - ratio);
    float invT = logf(r_therm / r25) / beta + 1.0f / 298.15f;
    float celsius = 1.0f / invT - 273.15f;
    return celsius * 1.8f + 32.0f;
}

float pressure_psi(int adc_raw, float psi_at_full_scale) {
    return ((float)adc_raw / (float)ADC_MAX_COUNT) * psi_at_full_scale;
}

float bosch_kpa(int adc_raw) {
    float v = ((float)adc_raw / (float)ADC_MAX_COUNT) * V_REF;
    return BOSCH_SLOPE * v + BOSCH_OFFSET;
}

float ewma_step(float current, float new_sample, float alpha) {
    return (1.0f - alpha) * current + alpha * new_sample;
}

#ifdef ARDUINO
#include <Arduino.h>

SensorState gSensorState = {0};

static ChannelHealth h_oil_temp;
static ChannelHealth h_post_sc_temp;
static ChannelHealth h_oil_press;
static ChannelHealth h_fuel_press;
static ChannelHealth h_pre_sc_press;

// Per-channel filtered raw ADC values
static float ewma_oil_temp     = 0.0f;
static float ewma_post_sc_temp = 0.0f;
static float ewma_oil_press    = 0.0f;
static float ewma_fuel_press   = 0.0f;
static float ewma_pre_sc_press = 0.0f;

static unsigned long boot_time_ms = 0;
static const unsigned long READY_DELAY_MS = 1000;

// Wire format is uint16_t (engineering units × 10). thermistor_to_F can return
// negatives for cold readings; casting a negative float to uint16_t is UB and
// would wrap to a garbage value on the CAN bus. Clamp to [0, 65535].
// Sensor electrical faults are signaled via the health bitmask (Phase D), not
// via magic temperature values, so clamping to 0 here is safe.
static inline uint16_t clamp_to_u16(float v) {
    if (v < 0.0f)        return 0;
    if (v > 65535.0f)    return 65535;
    return (uint16_t)v;
}

void sensor_pipeline_init() {
    pinMode(PIN_OIL_TEMP, INPUT);
    pinMode(PIN_POST_SC_TEMP, INPUT);
    pinMode(PIN_OIL_PRESS, INPUT);
    pinMode(PIN_FUEL_PRESS, INPUT);
    pinMode(PIN_PRE_SC_PRESS, INPUT);
    boot_time_ms = millis();

    // Prime EWMA with initial readings so first cycle isn't garbage
    ewma_oil_temp     = analogRead(PIN_OIL_TEMP);
    ewma_post_sc_temp = analogRead(PIN_POST_SC_TEMP);
    ewma_oil_press    = analogRead(PIN_OIL_PRESS);
    ewma_fuel_press   = analogRead(PIN_FUEL_PRESS);
    ewma_pre_sc_press = analogRead(PIN_PRE_SC_PRESS);

    channel_health_init(&h_oil_temp);
    channel_health_init(&h_post_sc_temp);
    channel_health_init(&h_oil_press);
    channel_health_init(&h_fuel_press);
    channel_health_init(&h_pre_sc_press);
}

void SensorPhase() {
    // Raw reads
    int raw_oil_temp     = analogRead(PIN_OIL_TEMP);
    int raw_post_sc_temp = analogRead(PIN_POST_SC_TEMP);
    int raw_oil_press    = analogRead(PIN_OIL_PRESS);
    int raw_fuel_press   = analogRead(PIN_FUEL_PRESS);
    int raw_pre_sc_press = analogRead(PIN_PRE_SC_PRESS);

    // EWMA filter
    ewma_oil_temp     = ewma_step(ewma_oil_temp,     raw_oil_temp,     EWMA_ALPHA_OIL_TEMP);
    ewma_post_sc_temp = ewma_step(ewma_post_sc_temp, raw_post_sc_temp, EWMA_ALPHA_POST_SC_T);
    ewma_oil_press    = ewma_step(ewma_oil_press,    raw_oil_press,    EWMA_ALPHA_OIL_PRESS);
    ewma_fuel_press   = ewma_step(ewma_fuel_press,   raw_fuel_press,   EWMA_ALPHA_FUEL_PRESS);
    ewma_pre_sc_press = ewma_step(ewma_pre_sc_press, raw_pre_sc_press, EWMA_ALPHA_PRE_SC_P);

    // Convert to engineering units × 10. Thermistors can return negative
    // floats for cold readings; clamp before the uint16_t cast (see above).
    // Pressure functions are mathematically non-negative so direct cast is safe.
    gSensorState.oil_temp_F_x10 = clamp_to_u16(thermistor_to_F(
        (int)ewma_oil_temp, OIL_TEMP_PULLUP_OHMS, OIL_TEMP_R25, OIL_TEMP_BETA) * 10.0f);

    gSensorState.post_sc_temp_F_x10 = clamp_to_u16(thermistor_to_F(
        (int)ewma_post_sc_temp, POST_SC_TEMP_PULLUP_OHMS, POST_SC_TEMP_R25, POST_SC_TEMP_BETA) * 10.0f);

    gSensorState.oil_pressure_psi_x10  = (uint16_t)(pressure_psi((int)ewma_oil_press,  OIL_PRESS_PSI_AT_FS) * 10.0f);
    gSensorState.fuel_pressure_psi_x10 = (uint16_t)(pressure_psi((int)ewma_fuel_press, FUEL_PRESS_PSI_AT_FS) * 10.0f);
    gSensorState.pre_sc_pressure_kpa_x10 = (uint16_t)(bosch_kpa((int)ewma_pre_sc_press) * 10.0f);

    uint8_t mask = 0;
    if (channel_health_update(&h_oil_temp, raw_oil_temp,
                              gSensorState.oil_temp_F_x10 / 10.0f,
                              plausibility_oil_temp_F)) {
        mask |= 0x01;
    }
    if (channel_health_update(&h_post_sc_temp, raw_post_sc_temp,
                              gSensorState.post_sc_temp_F_x10 / 10.0f,
                              plausibility_oil_temp_F)) {
        mask |= 0x02;
    }
    if (channel_health_update(&h_oil_press, raw_oil_press,
                              gSensorState.oil_pressure_psi_x10 / 10.0f,
                              plausibility_pressure_psi)) {
        mask |= 0x04;
    }
    if (channel_health_update(&h_fuel_press, raw_fuel_press,
                              gSensorState.fuel_pressure_psi_x10 / 10.0f,
                              plausibility_pressure_psi)) {
        mask |= 0x08;
    }
    if (channel_health_update(&h_pre_sc_press, raw_pre_sc_press,
                              gSensorState.pre_sc_pressure_kpa_x10 / 10.0f,
                              plausibility_kpa)) {
        mask |= 0x10;
    }
    gSensorState.health_bitmask = mask;

    gSensorState.age_ticks[0] = h_oil_temp.age_ticks;
    gSensorState.age_ticks[1] = h_post_sc_temp.age_ticks;
    gSensorState.age_ticks[2] = h_oil_press.age_ticks;
    gSensorState.age_ticks[3] = h_fuel_press.age_ticks;
    gSensorState.age_ticks[4] = h_pre_sc_press.age_ticks;

    // Ready flag clears for the first second
    gSensorState.ready_flag = (millis() - boot_time_ms >= READY_DELAY_MS) ? 1 : 0;
}
#endif
