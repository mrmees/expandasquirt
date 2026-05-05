#include "display_matrix.h"

#ifdef ARDUINO
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

void DisplayUpdate() {
    switch (current_mode) {
        case DISP_BOOT: draw_boot(); break;
        // Other modes filled in later tasks
        default:
            matrix.clear();
            break;
    }
}
#endif
