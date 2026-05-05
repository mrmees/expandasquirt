#ifndef BLE_CONSOLE_H
#define BLE_CONSOLE_H

#include <stdint.h>
#ifndef __cplusplus
#include <stdbool.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

bool ble_init();
void BleServicePhase();
void BleDumpPhase();
void ble_println(const char* msg);
bool ble_client_connected();

#ifdef __cplusplus
}
#endif

#endif
