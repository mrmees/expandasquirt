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

#ifdef __cplusplus
}
#endif

#endif
