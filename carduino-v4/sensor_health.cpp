#include "sensor_health.h"

bool electrical_fault(int adc_raw) {
    // Per design section 7.1: raw < 1% of FS or > 99% of FS.
    int low_thresh  = (int)(0.01f * (float)ADC_MAX_COUNT);
    int high_thresh = (int)(0.99f * (float)ADC_MAX_COUNT);
    return (adc_raw < low_thresh) || (adc_raw > high_thresh);
}

void debounce_init(DebounceState* d) {
    d->bad_count = 0;
    d->good_count = 0;
    d->asserted = false;
}

bool debounce_update(DebounceState* d, bool sample_bad) {
    if (sample_bad) {
        d->good_count = 0;
        if (d->bad_count < HEALTH_DEBOUNCE_BAD) d->bad_count++;
        if (d->bad_count >= HEALTH_DEBOUNCE_BAD) d->asserted = true;
    } else {
        d->bad_count = 0;
        if (d->good_count < HEALTH_DEBOUNCE_GOOD) d->good_count++;
        if (d->good_count >= HEALTH_DEBOUNCE_GOOD) d->asserted = false;
    }
    return d->asserted;
}
