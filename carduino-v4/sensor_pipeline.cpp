#include "sensor_pipeline.h"
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
