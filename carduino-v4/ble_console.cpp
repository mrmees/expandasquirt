#ifdef ARDUINO

#include "ble_console.h"
#include "config.h"
#include <ArduinoBLE.h>
#include <string.h>

static BLEService nus_service(BLE_SERVICE_UUID);
static BLECharacteristic tx_char(BLE_TX_CHAR_UUID, BLENotify, 20);
static BLECharacteristic rx_char(BLE_RX_CHAR_UUID, BLEWriteWithoutResponse | BLEWrite, 64);

static bool ble_ok = false;

struct CommandEntry {
    const char*    name;
    CommandHandler handler;
};

#define MAX_COMMANDS 16
static CommandEntry commands[MAX_COMMANDS];
static int n_commands = 0;

static char rx_buf[BLE_RX_BUFFER_SIZE];
static size_t rx_len = 0;

void ble_register_command(const char* name, CommandHandler h) {
    if (n_commands < MAX_COMMANDS) {
        commands[n_commands++] = { name, h };
    }
}

static void process_command(const char* line) {
    const char* space = strchr(line, ' ');
    char name[16] = {0};
    const char* args = "";
    if (space) {
        size_t nlen = space - line;
        if (nlen > 15) nlen = 15;
        memcpy(name, line, nlen);
        args = space + 1;
    } else {
        strncpy(name, line, 15);
    }

    for (int i = 0; i < n_commands; i++) {
        if (strcmp(commands[i].name, name) == 0) {
            commands[i].handler(args);
            return;
        }
    }
    ble_println("unknown command - type 'help'");
}

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

    if (rx_char.written()) {
        size_t n = rx_char.valueLength();
        const uint8_t* data = rx_char.value();
        for (size_t i = 0; i < n; i++) {
            char c = (char)data[i];
            if (c == '\r') continue;
            if (c == '\n') {
                rx_buf[rx_len] = 0;
                if (rx_len > 0) process_command(rx_buf);
                rx_len = 0;
            } else if (rx_len < BLE_RX_BUFFER_SIZE - 1) {
                rx_buf[rx_len++] = c;
            } else {
                rx_len = 0;
                ble_println("input buffer overflow");
            }
        }
    }
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
