#include "sensor_pipeline.h"
#include "can_protocol.h"
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

float pressure_psi_from_kpa_abs(int adc_raw, float kpa_at_full_scale) {
    float kpa_abs = ((float)adc_raw / (float)ADC_MAX_COUNT) * kpa_at_full_scale;
    float kpa_gauge = kpa_abs - ATM_KPA_NOMINAL;
    if (kpa_gauge < 0.0f) {
        kpa_gauge = 0.0f;
    }
    return kpa_gauge * 0.145038f;
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
#include <stddef.h>

SensorState gSensorState = {0};

static unsigned long boot_time_ms = 0;
static const unsigned long READY_DELAY_MS = 1000;

// Wire format is uint16_t (engineering units x 10). thermistor_to_F can return
// negatives for cold readings; casting a negative float to uint16_t is UB and
// would wrap to a garbage value on the CAN bus. Clamp to [0, 65535].
// Sensor electrical faults are signaled via the health bitmask (Phase D), not
// via magic temperature values, so clamping to 0 here is safe.
static inline uint16_t clamp_to_u16(float v) {
    if (v < 0.0f)        return 0;
    if (v > 65535.0f)    return 65535;
    return (uint16_t)v;
}

struct ThermistorCal {
    float pullup_ohms;
    float r25;
    float beta;
};

struct PressurePsiCal {
    float full_scale_psi;
};

struct PressureKpaAbsCal {
    float full_scale_kpa;
};

struct BoschKpaCal {};

typedef float (*ConvertFn)(int adc_raw, const void* cal);
typedef bool (*PlausibilityFn)(float val);

struct SensorChannel {
    const char* name;
    uint8_t pin;
    float ewma_alpha;
    ConvertFn convert;
    const void* cal;
    PlausibilityFn plausibility;
    uint8_t health_bit;
    uint16_t* output_x10;
};

struct ChannelState {
    float ewma;
    ChannelHealth health;
    FlatlineState flatline;
};

static float convert_thermistor(int adc_raw, const void* cal) {
    const ThermistorCal* c = (const ThermistorCal*)cal;
    return thermistor_to_F(adc_raw, c->pullup_ohms, c->r25, c->beta);
}

static float convert_pressure_psi(int adc_raw, const void* cal) {
    const PressurePsiCal* c = (const PressurePsiCal*)cal;
    return pressure_psi(adc_raw, c->full_scale_psi);
}

static float convert_pressure_kpa_abs(int adc_raw, const void* cal) {
    const PressureKpaAbsCal* c = (const PressureKpaAbsCal*)cal;
    return pressure_psi_from_kpa_abs(adc_raw, c->full_scale_kpa);
}

static float convert_bosch_kpa(int adc_raw, const void*) {
    return bosch_kpa(adc_raw);
}

static const ThermistorCal CAL_OIL_TEMP = {
    OIL_TEMP_PULLUP_OHMS, OIL_TEMP_R25, OIL_TEMP_BETA
};
static const ThermistorCal CAL_POST_SC_TEMP = {
    POST_SC_TEMP_PULLUP_OHMS, POST_SC_TEMP_R25, POST_SC_TEMP_BETA
};
static const PressureKpaAbsCal CAL_OIL_PRESS = { OIL_PRESS_KPA_AT_FS };
static const PressurePsiCal CAL_FUEL_PRESS = { FUEL_PRESS_PSI_AT_FS };
static const BoschKpaCal CAL_PRE_SC = {};

static const SensorChannel CHANNELS[] = {
    {"oil_temp", PIN_OIL_TEMP, EWMA_ALPHA_OIL_TEMP, convert_thermistor,
     &CAL_OIL_TEMP, plausibility_oil_temp_F, 0x01,
     &gSensorState.oil_temp_F_x10},
    {"post_sc_temp", PIN_POST_SC_TEMP, EWMA_ALPHA_POST_SC_T, convert_thermistor,
     &CAL_POST_SC_TEMP, plausibility_oil_temp_F, 0x02,
     &gSensorState.post_sc_temp_F_x10},
    {"oil_press", PIN_OIL_PRESS, EWMA_ALPHA_OIL_PRESS, convert_pressure_kpa_abs,
     &CAL_OIL_PRESS, plausibility_pressure_psi, 0x04,
     &gSensorState.oil_pressure_psi_x10},
    {"fuel_press", PIN_FUEL_PRESS, EWMA_ALPHA_FUEL_PRESS, convert_pressure_psi,
     &CAL_FUEL_PRESS, plausibility_pressure_psi, 0x08,
     &gSensorState.fuel_pressure_psi_x10},
    {"pre_sc_press", PIN_PRE_SC_PRESS, EWMA_ALPHA_PRE_SC_P, convert_bosch_kpa,
     &CAL_PRE_SC, plausibility_kpa, 0x10,
     &gSensorState.pre_sc_pressure_kpa_x10},
};

static const size_t NUM_CHANNELS = sizeof(CHANNELS) / sizeof(CHANNELS[0]);
static const size_t OIL_PRESS_CHANNEL_IDX = 2;

static ChannelState g_states[NUM_CHANNELS];

static bool engine_running_now(int raw_oil_press, float oil_press_psi) {
    // Primary: RPM from MS3 if recent (< 2 sec old).
    if (can_rpm_age_ms() < 2000) {
        return can_get_rpm() > ENGINE_RUNNING_RPM;
    }
    // Fallback: oil pressure threshold (single-tick health, no debounce).
    bool oil_press_healthy = !electrical_fault(raw_oil_press)
                             && plausibility_pressure_psi(oil_press_psi);
    if (oil_press_healthy) {
        return oil_press_psi > ENGINE_RUNNING_OIL_PSI;
    }
    // Both unavailable: assume running so flatline state is not falsely cleared.
    return true;
}

void sensor_pipeline_init() {
    boot_time_ms = millis();

    for (size_t i = 0; i < NUM_CHANNELS; i++) {
        pinMode(CHANNELS[i].pin, INPUT);

        // Prime EWMA with initial readings so first cycle isn't garbage.
        g_states[i].ewma = analogRead(CHANNELS[i].pin);
        channel_health_init(&g_states[i].health);
        flatline_init(&g_states[i].flatline);
    }
}

bool any_channel_flatlined(void) {
    for (size_t i = 0; i < NUM_CHANNELS; i++) {
        if (g_states[i].health.flatline) {
            return true;
        }
    }
    return false;
}

void SensorPhase() {
    unsigned long now_ms = millis();
    int raws[NUM_CHANNELS];

    // Convert to engineering units x 10. Thermistors can return negative
    // floats for cold readings; clamp before the uint16_t cast (see above).
    for (size_t i = 0; i < NUM_CHANNELS; i++) {
        const SensorChannel* ch = &CHANNELS[i];
        ChannelState* st = &g_states[i];

        raws[i] = analogRead(ch->pin);
        st->ewma = ewma_step(st->ewma, raws[i], ch->ewma_alpha);

        float val = ch->convert((int)st->ewma, ch->cal);
        *ch->output_x10 = clamp_to_u16(val * 10.0f);
    }

    bool eng = engine_running_now(
        raws[OIL_PRESS_CHANNEL_IDX],
        gSensorState.oil_pressure_psi_x10 / 10.0f);

    uint8_t mask = 0;
    for (size_t i = 0; i < NUM_CHANNELS; i++) {
        const SensorChannel* ch = &CHANNELS[i];
        ChannelState* st = &g_states[i];
        float val = *ch->output_x10 / 10.0f;

        if (channel_health_update(&st->health, raws[i], val,
                                  ch->plausibility,
                                  &st->flatline, now_ms, eng)) {
            mask |= ch->health_bit;
        }
        gSensorState.age_ticks[i] = st->health.age_ticks;
    }
    gSensorState.health_bitmask = mask;

    // Ready flag clears for the first second.
    gSensorState.ready_flag = (now_ms - boot_time_ms >= READY_DELAY_MS) ? 1 : 0;
}
#endif
