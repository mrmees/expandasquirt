#include "display_matrix.h"

#ifdef ARDUINO
#include "sensor_pipeline.h"
#include <Arduino.h>
#include <Arduino_LED_Matrix.h>

static ArduinoLEDMatrix matrix;
static uint8_t frame[8][12];
static DisplayMode current_mode = DISP_BOOT;
static uint8_t err_code = 0;
static uint8_t progress_pct = 0;
static unsigned long mode_entered_ms = 0;

static void clear_frame() {
    for (int r = 0; r < 8; r++) {
        for (int c = 0; c < 12; c++) {
            frame[r][c] = 0;
        }
    }
}

static void set_pixel(int col, int row, bool on) {
    if (col < 0 || col >= 12 || row < 0 || row >= 8) return;
    frame[row][col] = on ? 1 : 0;
}

static void render_frame() {
    matrix.renderBitmap(frame, 8, 12);
}

void display_init() {
    matrix.begin();
    matrix.clear();
}

void display_set_mode(DisplayMode m) {
    current_mode = m;
    mode_entered_ms = millis();
}

void display_set_error(uint8_t code) {
    err_code = code;
    current_mode = DISP_ERROR;
}

void display_set_progress(uint8_t pct) {
    progress_pct = pct;
}

// All status indicators are placed in column 11 (rightmost) so they remain
// visible when the MCP2515 shield is mounted on top of the R4. We lose most
// of the matrix's drawing area but gain at-a-glance status from the side.

static void draw_boot() {
    clear_frame();
    // Vertical scanning dot down col 11
    int row = (millis() / 100) % 8;
    set_pixel(11, row, true);
    render_frame();
}

static void draw_normal() {
    clear_frame();

    // 1 Hz heartbeat: col 11, row 0
    bool hb = ((millis() / 500) % 2) == 0;
    if (hb) set_pixel(11, 0, true);

    // 5 sensor health LEDs: col 11, rows 2-6 (one per channel, top→bottom)
    //   row 2: oil_temp        (bit 0x01)
    //   row 3: post_sc_temp    (bit 0x02)
    //   row 4: oil_press       (bit 0x04)
    //   row 5: fuel_press      (bit 0x08)
    //   row 6: pre_sc_press    (bit 0x10)
    for (int i = 0; i < 5; i++) {
        bool healthy = (gSensorState.health_bitmask >> i) & 1;
        if (healthy) set_pixel(11, 2 + i, true);
    }

    // Row 1 and row 7 left blank as visual separators.
    // BLE client connected indicator (Task 24) will land somewhere visible
    // in col 11 — TBD when we know what's free.

    render_frame();
}

// 3-pixel-wide x 5-pixel-tall digit font, encoded as 5 bytes per digit.
static const uint8_t digit_font[10][5] = {
    {0b111, 0b101, 0b101, 0b101, 0b111}, // 0
    {0b010, 0b110, 0b010, 0b010, 0b111}, // 1
    {0b111, 0b001, 0b111, 0b100, 0b111}, // 2
    {0b111, 0b001, 0b111, 0b001, 0b111}, // 3
    {0b101, 0b101, 0b111, 0b001, 0b001}, // 4
    {0b111, 0b100, 0b111, 0b001, 0b111}, // 5
    {0b111, 0b100, 0b111, 0b101, 0b111}, // 6
    {0b111, 0b001, 0b001, 0b001, 0b001}, // 7
    {0b111, 0b101, 0b111, 0b101, 0b111}, // 8
    {0b111, 0b101, 0b111, 0b001, 0b111}, // 9
};

static void draw_digit(int col, int row, int d) {
    if (d < 0 || d > 9) return;
    for (int r = 0; r < 5; r++) {
        for (int c = 0; c < 3; c++) {
            if (digit_font[d][r] & (1 << (2 - c))) {
                set_pixel(col + c, row + r, true);
            }
        }
    }
}

static void draw_error() {
    clear_frame();
    // Show two-digit error code.
    int tens = err_code / 10;
    int ones = err_code % 10;
    draw_digit(2, 1, tens);
    draw_digit(7, 1, ones);
    render_frame();
}

// v4.x maintenance / OTA patterns. All confined to col 11 (the always-visible
// strip when the MCP2515 shield is on top), since these states are mostly an
// "I am not in normal mode" cue — the phone app is the actual UI for OTA
// progress per V4X-DESIGN.md §9 (LED OTA progress out of scope for v1).

static void draw_countdown() {
    // MM_ARMED / MM_BLE_DRAIN / MM_WIFI_JOINING — slow 1 Hz blink at row 0
    clear_frame();
    if (((millis() / 500) % 2) == 0) set_pixel(11, 0, true);
    render_frame();
}

static void draw_ap_ready() {
    // MM_OTA_READY — solid col 11, "device is listening for OTA push"
    clear_frame();
    for (int r = 0; r < 8; r++) set_pixel(11, r, true);
    render_frame();
}

static void draw_uploading() {
    // MM_UPLOAD_APPLYING — chase down col 11 (only visible if poll() yields,
    // which it does between the byte-batch reads inside the JAndrassy loop)
    clear_frame();
    int phase = (millis() / 100) % 8;
    set_pixel(11, phase, true);
    render_frame();
}

static void draw_applying() {
    // MM_UPLOAD_APPLYING tail — fast strobe of full column. In practice we
    // rarely reach DISP_APPLYING because apply() resets the chip from inside
    // the library; included for completeness.
    clear_frame();
    if (((millis() / 100) % 2) == 0) {
        for (int r = 0; r < 8; r++) set_pixel(11, r, true);
    }
    render_frame();
}

void DisplayUpdate() {
    switch (current_mode) {
        case DISP_BOOT:      draw_boot();      break;
        case DISP_NORMAL:    draw_normal();    break;
        case DISP_COUNTDOWN: draw_countdown(); break;
        case DISP_AP_READY:  draw_ap_ready();  break;
        case DISP_UPLOADING: draw_uploading(); break;
        case DISP_APPLYING:  draw_applying();  break;
        case DISP_ERROR:     draw_error();     break;
        default:
            matrix.clear();
            break;
    }
}
#endif
