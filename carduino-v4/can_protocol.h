#ifndef CAN_PROTOCOL_H
#define CAN_PROTOCOL_H

#include "config.h"
#include "sensor_pipeline.h"

#ifdef __cplusplus
extern "C" {
#endif

bool can_protocol_init();
void CanSendPhase();
void CanReceivePhase();
uint16_t can_get_rpm();
unsigned long can_rpm_age_ms();

typedef struct {
    bool can_errors_warning;       // TXEP/RXEP set per Microchip
    bool can_busoff_active;        // TXBO set
    uint16_t busoff_recoveries;
    bool loop_timing_warn;         // last loop iteration > 50 ms
} SystemHealth;

extern SystemHealth gSystemHealth;

// Snapshot MCP2515 error flags + edge-detect bus-off recoveries.
// Called from CanSendPhase().
void system_health_update();

// Pack SensorState into 8-byte CAN Frame 1 payload (big-endian per design section 5.3)
void pack_frame1(const SensorState* s, uint8_t* out8);

// Pack SensorState into 8-byte CAN Frame 2 payload.
// status_flags and max_age supplied externally (computed in higher-level code).
void pack_frame2(const SensorState* s, uint8_t status_flags, uint8_t max_age, uint8_t* out8);

// Send one final Frame 2 with bit 2 of status_flags set (OTA in progress)
// before halting CAN broadcasts on entry to v4.x maintenance mode.
// Gives downstream loggers (TunerStudio) a clean "going down" marker
// instead of an unexplained data freeze. Per V4X-DESIGN.md §5.4.
void can_send_maintenance_marker();

#ifdef __cplusplus
}
#endif

#endif
