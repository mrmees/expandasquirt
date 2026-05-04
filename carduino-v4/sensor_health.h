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

void debounce_init(DebounceState* d);
// Returns true if asserted after this update.
bool debounce_update(DebounceState* d, bool sample_bad);

// Returns true if the engineering value is physically plausible for this car.
bool plausibility_oil_temp_F(float v);     // -40 to 350 F
bool plausibility_pressure_psi(float v);   // -5 to 200 PSI
bool plausibility_kpa(float v);            // 0 to 200 kPa absolute

#ifdef __cplusplus
}
#endif

#endif
