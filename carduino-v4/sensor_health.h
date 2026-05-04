#ifndef SENSOR_HEALTH_H
#define SENSOR_HEALTH_H

#include "config.h"
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool electrical_fault(int adc_raw);

#ifdef __cplusplus
}
#endif

#endif
