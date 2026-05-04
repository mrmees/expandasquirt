// tests/test_can_protocol.cpp

#include "sensor_pipeline.h"

extern "C" {
void pack_frame1(const SensorState* s, uint8_t* out8);
void pack_frame2(const SensorState* s, uint8_t status_flags, uint8_t max_age, uint8_t* out8);
}

TEST_CASE(pack_frame1_byte_order_big_endian) {
    SensorState s = {0};
    s.oil_temp_F_x10 = 0x1234;
    uint8_t out[8] = {0};
    pack_frame1(&s, out);
    ASSERT_EQ(out[0], 0x12);
    ASSERT_EQ(out[1], 0x34);
}

TEST_CASE(pack_frame1_all_fields) {
    SensorState s = {0};
    s.oil_temp_F_x10        = 1850;  // 185.0 F
    s.oil_pressure_psi_x10  = 584;   // 58.4 PSI
    s.fuel_pressure_psi_x10 = 461;   // 46.1 PSI
    s.pre_sc_pressure_kpa_x10 = 978; // 97.8 kPa
    uint8_t out[8] = {0};
    pack_frame1(&s, out);
    ASSERT_EQ(out[0], (1850 >> 8) & 0xFF);  ASSERT_EQ(out[1], 1850 & 0xFF);
    ASSERT_EQ(out[2], (584  >> 8) & 0xFF);  ASSERT_EQ(out[3], 584  & 0xFF);
    ASSERT_EQ(out[4], (461  >> 8) & 0xFF);  ASSERT_EQ(out[5], 461  & 0xFF);
    ASSERT_EQ(out[6], (978  >> 8) & 0xFF);  ASSERT_EQ(out[7], 978  & 0xFF);
}

TEST_CASE(pack_frame2_byte_layout) {
    SensorState s = {0};
    s.post_sc_temp_F_x10 = 1426;  // 142.6 F
    s.sequence_counter = 142;
    s.health_bitmask = 0x1F;      // all 5 sensors healthy

    uint8_t out[8] = {0};
    pack_frame2(&s, 0x01, 5, out);  // status flags = ready, max_age = 5

    ASSERT_EQ(out[0], (1426 >> 8) & 0xFF);
    ASSERT_EQ(out[1], 1426 & 0xFF);
    ASSERT_EQ(out[2], 0xFF);  // reserved
    ASSERT_EQ(out[3], 0xFF);  // reserved
    ASSERT_EQ(out[4], 142);   // seq
    ASSERT_EQ(out[5], 0x1F);  // health
    ASSERT_EQ(out[6], 0x01);  // status flags
    ASSERT_EQ(out[7], 5);     // age
}
