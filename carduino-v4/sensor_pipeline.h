#ifndef SENSOR_PIPELINE_H
#define SENSOR_PIPELINE_H

#include "config.h"
#include <stdint.h>

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

// For ratiometric absolute-pressure sensors with linear V/P transfer.
// Returns gauge pressure in PSI, clamped to >= 0 (gauge can't be negative
// for oil pressure use; if the abs reading is below atmospheric, report 0).
float pressure_psi_from_kpa_abs(int adc_raw, float kpa_at_full_scale);

// Convert raw ADC reading from the Bosch 0 261 230 146 MAP sensor
// to kPa absolute. Uses BOSCH_SLOPE / BOSCH_OFFSET from config.h.
float bosch_kpa(int adc_raw);

// Single EWMA step. Returns the new filtered value.
// alpha in (0, 1]: higher = more responsive, lower = more filtered.
float ewma_step(float current, float new_sample, float alpha);

typedef struct {
    uint16_t  oil_temp_F_x10;
    uint16_t  post_sc_temp_F_x10;
    uint16_t  oil_pressure_psi_x10;
    uint16_t  fuel_pressure_psi_x10;
    uint16_t  pre_sc_pressure_kpa_x10;
    uint8_t   age_ticks[5];
    uint8_t   health_bitmask;     // populated in Phase D
    uint8_t   ready_flag;
    uint8_t   sequence_counter;
} SensorState;

extern SensorState gSensorState;

void sensor_pipeline_init();
void SensorPhase();   // called at SENSOR_HZ from main loop

// True if any sensor channel is currently flatlined.
bool any_channel_flatlined(void);

#ifdef __cplusplus
}
#endif

#endif
