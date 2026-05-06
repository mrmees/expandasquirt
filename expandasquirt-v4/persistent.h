#ifndef PERSISTENT_H
#define PERSISTENT_H

#include <stdint.h>

typedef enum {
    RESET_POWER_ON = 1,
    RESET_WATCHDOG = 2,
    RESET_BROWNOUT = 3,
    RESET_SOFT     = 4,
    RESET_UNKNOWN  = 0xFF
} ResetCause;

typedef struct {
    uint32_t magic;          // 0xCAFEBABE
    uint8_t  last_reset_cause;
    uint16_t boot_counter;
    uint8_t  last_fatal_err;
    uint16_t crc;            // simple checksum
} PersistentState;

#ifdef __cplusplus
extern "C" {
#endif

void persistent_init();
const PersistentState* persistent_get();
void persistent_set_fatal_err(uint8_t err);
void persistent_record_boot(ResetCause cause);
ResetCause read_reset_cause();

#ifdef __cplusplus
}
#endif

#endif
