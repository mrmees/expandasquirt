#include "sensor_health.h"

bool electrical_fault(int adc_raw) {
    // Per design section 7.1: raw < 1% of FS or > 99% of FS.
    int low_thresh  = (int)(0.01f * (float)ADC_MAX_COUNT);
    int high_thresh = (int)(0.99f * (float)ADC_MAX_COUNT);
    return (adc_raw < low_thresh) || (adc_raw > high_thresh);
}
