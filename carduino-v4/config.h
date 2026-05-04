#ifndef CONFIG_H
#define CONFIG_H

// ===== Pin map (per DESIGN.md §2.3) =====
#define PIN_OIL_TEMP     A0
#define PIN_POST_SC_TEMP A1
#define PIN_OIL_PRESS    A2
#define PIN_FUEL_PRESS   A3
#define PIN_PRE_SC_PRESS A4
// A5 reserved
#define PIN_MCP2515_CS   10
#define PIN_MCP2515_INT  2

// ===== ADC =====
#define ADC_RESOLUTION_BITS 14
#define ADC_MAX_COUNT       16383   // 2^14 - 1
#define V_REF               5.0f

// ===== Loop scheduling (Hz) =====
#define SENSOR_HZ      100
#define CAN_SEND_HZ    10
#define BLE_DUMP_HZ    5
#define DISPLAY_HZ     10
#define SENSOR_PERIOD_MS  (1000 / SENSOR_HZ)
#define CAN_PERIOD_MS     (1000 / CAN_SEND_HZ)
#define BLE_PERIOD_MS     (1000 / BLE_DUMP_HZ)
#define DISPLAY_PERIOD_MS (1000 / DISPLAY_HZ)

// ===== EWMA alpha per sensor =====
#define EWMA_ALPHA_OIL_TEMP    0.05f
#define EWMA_ALPHA_POST_SC_T   0.10f
#define EWMA_ALPHA_OIL_PRESS   0.10f
#define EWMA_ALPHA_FUEL_PRESS  0.10f
#define EWMA_ALPHA_PRE_SC_P    0.10f

// ===== Thermistor parameters =====
#define OIL_TEMP_PULLUP_OHMS  10000.0f
#define OIL_TEMP_R25          10000.0f
#define OIL_TEMP_BETA         3950.0f

#define POST_SC_TEMP_PULLUP_OHMS  2490.0f
#define POST_SC_TEMP_R25          3520.0f
#define POST_SC_TEMP_BETA         3984.0f

// ===== Pressure sensor full-scale =====
#define OIL_PRESS_PSI_AT_FS   100.0f
#define FUEL_PRESS_PSI_AT_FS  100.0f

// ===== Bosch 0 261 230 146 transfer function =====
#define BOSCH_SLOPE   24.7f   // kPa per V
#define BOSCH_OFFSET  0.12f   // kPa offset

// ===== CAN =====
#define CAN_TX_FRAME1_ID  1025  // 0x401
#define CAN_TX_FRAME2_ID  1026  // 0x402
#define CAN_RX_RPM_ID     1512  // 0x5E8 (MS3 dash broadcast)
#define CAN_BITRATE       CAN_500KBPS
#define CAN_CRYSTAL       MCP_16MHZ

// ===== Watchdog =====
#define WATCHDOG_NORMAL_MS       1000
#define WATCHDOG_MAINTENANCE_MS  8000

// ===== Health thresholds =====
#define HEALTH_DEBOUNCE_BAD   3   // samples
#define HEALTH_DEBOUNCE_GOOD  10  // samples
#define FLATLINE_TIMEOUT_MS   5000
#define ENGINE_RUNNING_RPM    500
#define ENGINE_RUNNING_OIL_PSI 5

// ===== BLE =====
#define BLE_DEVICE_NAME       "CARDUINO-v4"
#define BLE_SERVICE_UUID      "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define BLE_TX_CHAR_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
#define BLE_RX_CHAR_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define BLE_RX_BUFFER_SIZE    64

// ===== AP =====
#define AP_SSID  "CARDUINO-OTA"
// AP_PASSWORD comes from secrets.h
#define AP_GATEWAY_IP_LAST_OCTET  1   // 192.168.4.1

#endif
