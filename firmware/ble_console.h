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
typedef void (*CommandHandler)(const char* args);
void ble_register_command(const char* name, CommandHandler h);

// Tear down the BLE stack entirely. Called by the v4.x maintenance state
// machine before bringing up WiFi STA — V4X-DESIGN.md §4.4 / §6 explicitly
// rejects dual-stack BLE+WiFi at runtime, so the transports are strictly
// serial. After ble_end(), no further BLE traffic is possible until reboot.
void ble_end();

#ifdef __cplusplus
}
#endif

#endif
