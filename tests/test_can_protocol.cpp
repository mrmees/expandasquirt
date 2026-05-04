// tests/test_can_protocol.cpp

#include "sensor_pipeline.h"

extern "C" {
void pack_frame1(const SensorState* s, uint8_t* out8);
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
