#ifdef ARDUINO

#include "ble_console.h"
#include "config.h"
#include <ArduinoBLE.h>
#include <string.h>

static BLEService nus_service(BLE_SERVICE_UUID);
static BLECharacteristic tx_char(BLE_TX_CHAR_UUID, BLENotify, 20);
static BLECharacteristic rx_char(BLE_RX_CHAR_UUID, BLEWriteWithoutResponse | BLEWrite, 64);

static bool ble_ok = false;

bool ble_init() {
    if (!BLE.begin()) {
        Serial.println(F("BLE.begin() failed"));
        return false;
    }

    BLE.setDeviceName(BLE_DEVICE_NAME);
    BLE.setLocalName(BLE_DEVICE_NAME);
    BLE.setAdvertisedService(nus_service);

    nus_service.addCharacteristic(tx_char);
    nus_service.addCharacteristic(rx_char);
    BLE.addService(nus_service);

    BLE.advertise();
    ble_ok = true;
    Serial.println(F("BLE advertising as " BLE_DEVICE_NAME));
    return true;
}

void BleServicePhase() {
    if (!ble_ok) return;
    BLE.poll();
}

bool ble_client_connected() {
    return BLE.central().connected();
}

void ble_println(const char* msg) {
    if (!ble_ok || !ble_client_connected()) return;

    size_t len = strlen(msg);
    while (len > 0) {
        size_t chunk = (len > 19) ? 19 : len;
        tx_char.writeValue((const uint8_t*)msg, chunk);
        msg += chunk;
        len -= chunk;
    }

    const char* eol = "\r\n";
    tx_char.writeValue((const uint8_t*)eol, 2);
}

void BleDumpPhase() {
}

#endif
