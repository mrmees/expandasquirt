#include "can_protocol.h"

void pack_frame1(const SensorState* s, uint8_t* out8) {
    out8[0] = (s->oil_temp_F_x10        >> 8) & 0xFF;
    out8[1] =  s->oil_temp_F_x10               & 0xFF;
    out8[2] = (s->oil_pressure_psi_x10  >> 8) & 0xFF;
    out8[3] =  s->oil_pressure_psi_x10         & 0xFF;
    out8[4] = (s->fuel_pressure_psi_x10 >> 8) & 0xFF;
    out8[5] =  s->fuel_pressure_psi_x10        & 0xFF;
    out8[6] = (s->pre_sc_pressure_kpa_x10 >> 8) & 0xFF;
    out8[7] =  s->pre_sc_pressure_kpa_x10        & 0xFF;
}

void pack_frame2(const SensorState* s, uint8_t status_flags, uint8_t max_age, uint8_t* out8) {
    out8[0] = (s->post_sc_temp_F_x10 >> 8) & 0xFF;
    out8[1] =  s->post_sc_temp_F_x10        & 0xFF;
    out8[2] = 0xFF;  // reserved (future CAN ADC06)
    out8[3] = 0xFF;
    out8[4] = s->sequence_counter;
    out8[5] = s->health_bitmask;
    out8[6] = status_flags;
    out8[7] = max_age;
}

#ifdef ARDUINO

#include <SPI.h>
#include <mcp2515.h>

static MCP2515 mcp2515(PIN_MCP2515_CS);

static bool selftest_loopback() {
    mcp2515.setLoopbackMode();

    struct can_frame tx;
    tx.can_id  = 0x123;
    tx.can_dlc = 4;
    tx.data[0] = 0xDE; tx.data[1] = 0xAD;
    tx.data[2] = 0xBE; tx.data[3] = 0xEF;
    if (mcp2515.sendMessage(&tx) != MCP2515::ERROR_OK) return false;

    delay(5);

    struct can_frame rx;
    if (mcp2515.readMessage(&rx) != MCP2515::ERROR_OK) return false;
    if (rx.can_id  != 0x123) return false;
    if (rx.can_dlc != 4)     return false;
    if (rx.data[0] != 0xDE || rx.data[1] != 0xAD) return false;
    if (rx.data[2] != 0xBE || rx.data[3] != 0xEF) return false;

    return true;
}

bool can_protocol_init() {
    SPI.begin();
    if (mcp2515.reset() != MCP2515::ERROR_OK) {
        Serial.println(F("MCP2515 reset failed"));
        return false;
    }
    if (mcp2515.setBitrate(CAN_BITRATE, CAN_CRYSTAL) != MCP2515::ERROR_OK) {
        Serial.println(F("MCP2515 setBitrate failed"));
        return false;
    }
    if (!selftest_loopback()) {
        Serial.println(F("MCP2515 loopback self-test failed"));
        return false;
    }
    if (mcp2515.setNormalMode() != MCP2515::ERROR_OK) {
        Serial.println(F("MCP2515 setNormalMode failed"));
        return false;
    }
    Serial.println(F("MCP2515 init OK"));
    return true;
}

void CanSendPhase() {
    static uint8_t seq = 0;
    gSensorState.sequence_counter = seq++;

    uint8_t buf[8];

    // Frame 1
    pack_frame1(&gSensorState, buf);
    struct can_frame f1;
    f1.can_id  = CAN_TX_FRAME1_ID;
    f1.can_dlc = 8;
    memcpy(f1.data, buf, 8);
    mcp2515.sendMessage(&f1);

    // Frame 2 — for now, status flags = ready bit only, max_age = 0
    uint8_t status_flags = gSensorState.ready_flag ? 0x01 : 0x00;
    pack_frame2(&gSensorState, status_flags, 0, buf);
    struct can_frame f2;
    f2.can_id  = CAN_TX_FRAME2_ID;
    f2.can_dlc = 8;
    memcpy(f2.data, buf, 8);
    mcp2515.sendMessage(&f2);
}

#endif
