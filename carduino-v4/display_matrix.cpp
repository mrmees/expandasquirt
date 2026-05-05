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

static void draw_boot() {
    clear_frame();
    // Simple scanning dot animation
    int col = (millis() / 100) % 12;
    set_pixel(col, 4, true);
    render_frame();
}

static void draw_normal() {
    clear_frame();

    // 1 Hz heartbeat in top-right corner (col 11, row 0)
    bool hb = ((millis() / 500) % 2) == 0;
    if (hb) set_pixel(11, 0, true);

    // BLE client connected indicator in top-left (col 0, row 0)
    // Wired in Task 24; for now, leave off.

    // Bottom row: 5 sensor health LEDs (cols 0-4 of row 7)
    for (int i = 0; i < 5; i++) {
        bool healthy = (gSensorState.health_bitmask >> i) & 1;
        if (healthy) set_pixel(i, 7, true);
    }

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

void DisplayUpdate() {
    switch (current_mode) {
        case DISP_BOOT: draw_boot(); break;
        case DISP_NORMAL: draw_normal(); break;
        case DISP_ERROR: draw_error(); break;
        // Other modes filled in later tasks
        default:
            matrix.clear();
            break;
    }
}
#endif
