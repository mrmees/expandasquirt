#ifndef CAN_PROTOCOL_H
#define CAN_PROTOCOL_H

#include "config.h"
#include "sensor_pipeline.h"

#ifdef __cplusplus
extern "C" {
#endif

bool can_protocol_init();
void CanSendPhase();

// Pack SensorState into 8-byte CAN Frame 1 payload (big-endian per design section 5.3)
void pack_frame1(const SensorState* s, uint8_t* out8);

// Pack SensorState into 8-byte CAN Frame 2 payload.
// status_flags and max_age supplied externally (computed in higher-level code).
void pack_frame2(const SensorState* s, uint8_t status_flags, uint8_t max_age, uint8_t* out8);

#ifdef __cplusplus
}
#endif

#endif
