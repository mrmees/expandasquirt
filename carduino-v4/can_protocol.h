#ifndef CAN_PROTOCOL_H
#define CAN_PROTOCOL_H

#include "config.h"
#include "sensor_pipeline.h"

#ifdef __cplusplus
extern "C" {
#endif

bool can_protocol_init();
void CanSendPhase();

#ifdef __cplusplus
}
#endif

#endif
