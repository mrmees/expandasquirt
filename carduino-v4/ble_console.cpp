#ifdef ARDUINO

#include "ble_console.h"
#include "config.h"
#include "persistent.h"
#include "sensor_pipeline.h"
#include <ArduinoBLE.h>
#include <stdio.h>
#include <string.h>

static BLEService nus_service(BLE_SERVICE_UUID);
static BLECharacteristic tx_char(BLE_TX_CHAR_UUID, BLENotify, 20);
static BLECharacteristic rx_char(BLE_RX_CHAR_UUID, BLEWriteWithoutResponse | BLEWrite, 64);

static bool ble_ok = false;
static bool was_connected = false;

struct CommandEntry {
    const char*    name;
    CommandHandler handler;
};

#define MAX_COMMANDS 16
static CommandEntry commands[MAX_COMMANDS];
static int n_commands = 0;

static char rx_buf[BLE_RX_BUFFER_SIZE];
static size_t rx_len = 0;

// Forward decl: definition is below BleDumpPhase, but cmd_status calls it.
static void do_sensor_dump();

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
    // Always emit on demand, regardless of verbose toggle.
    if (!ble_ok || !ble_client_connected()) return;
    do_sensor_dump();
}

static void cmd_cal(const char* args) {
    char buf[64];
    int pin = -1;
    if      (strcmp(args, "therm1") == 0) pin = PIN_OIL_TEMP;
    else if (strcmp(args, "therm2") == 0) pin = PIN_POST_SC_TEMP;
    else if (strcmp(args, "pres1")  == 0) pin = PIN_OIL_PRESS;
    else if (strcmp(args, "pres2")  == 0) pin = PIN_FUEL_PRESS;
    else if (strcmp(args, "pres3")  == 0) pin = PIN_PRE_SC_PRESS;
    else {
        ble_println("usage: cal <therm1|therm2|pres1|pres2|pres3>");
        return;
    }
    int raw = analogRead(pin);
    float v = (raw / (float)ADC_MAX_COUNT) * V_REF;
    snprintf(buf, sizeof(buf), "  raw=%d  voltage=%.3fV", raw, v);
    ble_println(buf);
}

static bool verbose_enabled = true;

static void cmd_reboot(const char* args) {
    (void)args;
    ble_println("rebooting in 1 sec...");
    delay(1000);
    NVIC_SystemReset();
}

static void cmd_boot(const char* args) {
    (void)args;
    char buf[64];
    const PersistentState* s = persistent_get();
    const char* cause_names[] = {
        "UNKNOWN", "POWER_ON", "WATCHDOG", "BROWNOUT", "SOFT_RESET"
    };
    int idx = (s->last_reset_cause <= 4) ? s->last_reset_cause : 0;
    snprintf(buf, sizeof(buf), "boot=%u reset=%s last_err=%u",
             s->boot_counter, cause_names[idx], s->last_fatal_err);
    ble_println(buf);
}

static void cmd_verbose(const char* args) {
    if (strcmp(args, "on") == 0) {
        verbose_enabled = true;
        ble_println("verbose on");
    } else if (strcmp(args, "off") == 0) {
        verbose_enabled = false;
        ble_println("verbose off");
    } else {
        ble_println("usage: verbose <on|off>");
    }
}

static void cmd_help(const char* args) {
    (void)args;
    ble_println("commands:");
    ble_println("  status            - one-shot sensor dump");
    ble_println("  cal <ch>          - raw ADC + voltage (therm1|2, pres1|2|3)");
    ble_println("  boot              - reset cause, boot count, last fatal error");
    ble_println("  verbose on|off    - toggle periodic dump");
    ble_println("  reboot            - soft reset");
    ble_println("  help              - this list");
    // More commands land as later tasks ship: log, reset can/ble,
    // clear errors, selftest, maintenance/abort.
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

    ble_register_command("status",  cmd_status);
    ble_register_command("cal",     cmd_cal);
    ble_register_command("boot",    cmd_boot);
    ble_register_command("reboot",  cmd_reboot);
    ble_register_command("verbose", cmd_verbose);
    ble_register_command("help",    cmd_help);

    BLE.advertise();
    ble_ok = true;
    Serial.println(F("BLE advertising as " BLE_DEVICE_NAME));
    return true;
}

void BleServicePhase() {
    if (!ble_ok) return;
    BLE.poll();

    bool now_connected = ble_client_connected();
    if (now_connected && !was_connected) {
        char buf[64];
        snprintf(buf, sizeof(buf), "CARDUINO v4 connected (uptime %lu sec)", millis() / 1000);
        ble_println(buf);
        ble_println("type 'help' for commands");
    }

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

    was_connected = now_connected;
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

// Internal dump body. Caller is responsible for connection/verbose gating.
// Single-line packed format optimized for human reading at 1 Hz cadence:
//   [..XX.] oilT=58.1F postT=63.6F oilP=66.2psi fuelP=66.4psi preP=26.7kPa  flat=Y age=15 seq=141
// The 5-char [hHhh.] prefix is positional: dot=healthy, X=any-fault, in
// channel order (oilT, postT, oilP, fuelP, preP). 'flat=Y' suffix appears
// only when at least one channel is flatlined. 'age' is max held-value age
// across channels, in 100 ms units (saturates at 25.5 s).
static void do_sensor_dump() {
    char hbits[6];
    for (int i = 0; i < 5; i++) {
        hbits[i] = (gSensorState.health_bitmask & (1 << i)) ? '.' : 'X';
    }
    hbits[5] = '\0';

    uint8_t max_age = 0;
    for (int i = 0; i < 5; i++) {
        if (gSensorState.age_ticks[i] > max_age) max_age = gSensorState.age_ticks[i];
    }

    char buf[128];
    snprintf(buf, sizeof(buf),
             "[%s] oilT=%.1fF postT=%.1fF oilP=%.1fpsi fuelP=%.1fpsi preP=%.1fkPa%s age=%u seq=%u",
             hbits,
             gSensorState.oil_temp_F_x10        / 10.0f,
             gSensorState.post_sc_temp_F_x10    / 10.0f,
             gSensorState.oil_pressure_psi_x10  / 10.0f,
             gSensorState.fuel_pressure_psi_x10 / 10.0f,
             gSensorState.pre_sc_pressure_kpa_x10 / 10.0f,
             any_channel_flatlined() ? "  flat=Y" : "",
             (unsigned)max_age,
             (unsigned)gSensorState.sequence_counter);
    ble_println(buf);
}

void BleDumpPhase() {
    if (!ble_ok || !ble_client_connected()) return;
    if (!verbose_enabled) return;  // periodic dumps gated by verbose toggle
    do_sensor_dump();
}

#endif
