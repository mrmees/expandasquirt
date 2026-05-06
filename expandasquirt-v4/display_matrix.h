#ifndef DISPLAY_MATRIX_H
#define DISPLAY_MATRIX_H

#include <stdint.h>

typedef enum {
    DISP_BOOT,
    DISP_NORMAL,
    DISP_COUNTDOWN,
    DISP_AP_READY,
    DISP_UPLOADING,
    DISP_APPLYING,
    DISP_ERROR
} DisplayMode;

#ifdef __cplusplus
extern "C" {
#endif

void display_init();
void display_set_mode(DisplayMode m);
void display_set_error(uint8_t err_code);    // for DISP_ERROR
void display_set_progress(uint8_t percent);  // for DISP_UPLOADING
void DisplayUpdate();                        // called at DISPLAY_HZ from main loop

#ifdef __cplusplus
}
#endif

#endif
