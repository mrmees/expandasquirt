#ifndef SENSOR_PIPELINE_H
#define SENSOR_PIPELINE_H

#include "config.h"

#ifdef __cplusplus
extern "C" {
#endif

// Convert raw ADC reading from a thermistor voltage divider to F.
// Uses single-B-parameter Steinhart-Hart equation.
// Topology: pull-up resistor (pullup_ohms) between V+ and ADC pin,
//           thermistor between ADC pin and GND.
float thermistor_to_F(int adc_raw, float pullup_ohms, float r25, float beta);

// Convert raw ADC reading from a ratiometric 0-V_REF pressure sensor
// to PSI. Assumes 0V = 0 PSI, V_REF = psi_at_full_scale linear.
float pressure_psi(int adc_raw, float psi_at_full_scale);

// Convert raw ADC reading from the Bosch 0 261 230 146 MAP sensor
// to kPa absolute. Uses BOSCH_SLOPE / BOSCH_OFFSET from config.h.
float bosch_kpa(int adc_raw);

// Single EWMA step. Returns the new filtered value.
// alpha in (0, 1]: higher = more responsive, lower = more filtered.
float ewma_step(float current, float new_sample, float alpha);

#ifdef __cplusplus
}
#endif

#endif
