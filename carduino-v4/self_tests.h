#ifndef SELF_TESTS_H
#define SELF_TESTS_H

#include <stdint.h>
#ifndef __cplusplus
#include <stdbool.h>
#endif

typedef enum {
    ERR_NONE = 0,
    ERR_ADC  = 1,   // Vref check failed (per design section 7.7)
    ERR_CAN  = 2,   // SPI read or loopback failed
    ERR_BLE  = 3,   // BLE init failed
    ERR_OTA  = 99,  // OTA apply failed (set externally)
} ErrCode;

#ifdef __cplusplus
extern "C" {
#endif

// Returns the highest-priority error encountered, or ERR_NONE if all pass.
// Halts on ERR_ADC; degrades on ERR_CAN/BLE; logs only on LED matrix init.
ErrCode run_boot_self_tests();

// True if CAN was available after self-tests. False = degraded mode.
bool self_test_can_available();

#ifdef __cplusplus
}
#endif

#endif
