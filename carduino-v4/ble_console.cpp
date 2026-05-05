#ifdef ARDUINO

#include "ble_console.h"
#include "config.h"
#include "sensor_pipeline.h"
#include <ArduinoBLE.h>
#include <stdio.h>
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

static void format_status_line(char* out, size_t outlen) {
    snprintf(out, outlen,
             "[seq=%u ready=%u health=0x%02X]",
             gSensorState.sequence_counter,
             gSensorState.ready_flag,
             gSensorState.health_bitmask);
}

static void format_sensor_line(char* out, size_t outlen, const char* label,
                               uint16_t value_x10, const char* unit, uint8_t bit) {
    bool healthy = (gSensorState.health_bitmask & bit) != 0;
    snprintf(out, outlen, "  %-6s = %6.1f %-3s   %s",
             label, value_x10 / 10.0f, unit,
             healthy ? "ok" : "FAULT");
}

static void cmd_status(const char* args) {
    (void)args;
    BleDumpPhase();
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

    ble_register_command("status", cmd_status);

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
    if (!ble_ok || !ble_client_connected()) return;

    char buf[64];
    format_status_line(buf, sizeof(buf));
    ble_println(buf);

    format_sensor_line(buf, sizeof(buf), "oilT",  gSensorState.oil_temp_F_x10,         "F",   0x01);
    ble_println(buf);
    format_sensor_line(buf, sizeof(buf), "postT", gSensorState.post_sc_temp_F_x10,     "F",   0x02);
    ble_println(buf);
    format_sensor_line(buf, sizeof(buf), "oilP",  gSensorState.oil_pressure_psi_x10,   "PSI", 0x04);
    ble_println(buf);
    format_sensor_line(buf, sizeof(buf), "fuelP", gSensorState.fuel_pressure_psi_x10,  "PSI", 0x08);
    ble_println(buf);
    format_sensor_line(buf, sizeof(buf), "preP",  gSensorState.pre_sc_pressure_kpa_x10, "kPa", 0x10);
    ble_println(buf);
}

#endif
