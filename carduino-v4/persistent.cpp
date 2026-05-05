#ifdef ARDUINO

#include "persistent.h"
#include <EEPROM.h>
#include <stddef.h>

static PersistentState state = {0};
static const int EEPROM_BASE = 0;

static uint16_t calc_crc(const PersistentState* s) {
    uint16_t crc = 0;
    const uint8_t* p = (const uint8_t*)s;
    for (size_t i = 0; i < offsetof(PersistentState, crc); i++) {
        crc = ((crc << 1) | (crc >> 15)) ^ p[i];
    }
    return crc;
}

static bool load_from_eeprom() {
    EEPROM.get(EEPROM_BASE, state);
    if (state.magic != 0xCAFEBABE) return false;
    if (state.crc != calc_crc(&state)) return false;
    return true;
}

static void save_to_eeprom() {
    state.crc = calc_crc(&state);
    EEPROM.put(EEPROM_BASE, state);
}

void persistent_init() {
    if (!load_from_eeprom()) {
        state.magic = 0xCAFEBABE;
        state.last_reset_cause = RESET_UNKNOWN;
        state.boot_counter = 0;
        state.last_fatal_err = 0;
    }
}

const PersistentState* persistent_get() { return &state; }

void persistent_set_fatal_err(uint8_t err) {
    state.last_fatal_err = err;
    save_to_eeprom();
}

void persistent_record_boot(ResetCause cause) {
    state.last_reset_cause = cause;
    state.boot_counter++;
    save_to_eeprom();
}

#endif
