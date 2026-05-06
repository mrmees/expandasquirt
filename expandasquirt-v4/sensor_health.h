#ifndef SENSOR_HEALTH_H
#define SENSOR_HEALTH_H

#include "config.h"
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool electrical_fault(int adc_raw);

typedef struct DebounceState {
    uint8_t bad_count;
    uint8_t good_count;
    bool    asserted;
} DebounceState;

typedef struct {
    DebounceState electrical_db;
    DebounceState plausibility_db;
    bool          flatline;       // populated in Task 19; default false
    uint8_t       age_ticks;      // 100 ms units, saturates at 255 (= 25.5 s)
    uint8_t       tick_subdiv;    // internal: counts 0..9 to divide 100 Hz to 10 Hz
} ChannelHealth;

typedef struct {
    float         last_value;
    unsigned long stable_since_ms;
    bool          flatline_asserted;
} FlatlineState;

void debounce_init(DebounceState* d);
// Returns true if asserted after this update.
bool debounce_update(DebounceState* d, bool sample_bad);

void flatline_init(FlatlineState* f);
bool flatline_update(FlatlineState* f, float current_value, unsigned long now_ms,
                     bool engine_running);

void channel_health_init(ChannelHealth* ch);

// Returns true if the channel is healthy (all dimensions clean).
// raw_adc is the raw ADC reading; eng_value is the converted engineering value.
bool channel_health_update(ChannelHealth* ch, int raw_adc, float eng_value,
                           bool (*plausibility_fn)(float),
                           FlatlineState* flat, unsigned long now_ms,
                           bool engine_running);

// Returns true if the engineering value is physically plausible for this car.
bool plausibility_oil_temp_F(float v);     // -40 to 350 F
bool plausibility_pressure_psi(float v);   // -5 to 200 PSI
bool plausibility_kpa(float v);            // 0 to 200 kPa absolute

#ifdef __cplusplus
}
#endif

#endif
