#include "sensor_health.h"
#include <math.h>

bool electrical_fault(int adc_raw) {
    // Per design section 7.1: raw < 1% of FS or > 99% of FS.
    int low_thresh  = (int)(0.01f * (float)ADC_MAX_COUNT);
    int high_thresh = (int)(0.99f * (float)ADC_MAX_COUNT);
    return (adc_raw < low_thresh) || (adc_raw > high_thresh);
}

void debounce_init(DebounceState* d) {
    d->bad_count = 0;
    d->good_count = 0;
    d->asserted = false;
}

bool debounce_update(DebounceState* d, bool sample_bad) {
    if (sample_bad) {
        d->good_count = 0;
        if (d->bad_count < HEALTH_DEBOUNCE_BAD) d->bad_count++;
        if (d->bad_count >= HEALTH_DEBOUNCE_BAD) d->asserted = true;
    } else {
        d->bad_count = 0;
        if (d->good_count < HEALTH_DEBOUNCE_GOOD) d->good_count++;
        if (d->good_count >= HEALTH_DEBOUNCE_GOOD) d->asserted = false;
    }
    return d->asserted;
}

void channel_health_init(ChannelHealth* ch) {
    debounce_init(&ch->electrical_db);
    debounce_init(&ch->plausibility_db);
    ch->flatline = false;
    ch->age_ticks = 0;
    ch->tick_subdiv = 0;
    // TODO: age_ticks is documented as 100 ms units, but the implementation
    // uses tick_subdiv to count SensorPhase calls (10 calls per increment).
    // On real hardware SensorPhase runs at ~70 Hz (BLE.poll() and analogRead
    // overhead), so each "tick" is actually ~143 ms wall time. Age direction
    // is correct, magnitude is ~30% under-reported. To fix: replace
    // tick_subdiv with a wall-clock delta from now_ms (already passed in).
    // Will require updating the test helper to pass simulated now_ms.
}

void flatline_init(FlatlineState* f) {
    f->last_value = 0.0f;
    f->stable_since_ms = 0;
    f->flatline_asserted = false;
}

bool flatline_update(FlatlineState* f, float current_value, unsigned long now_ms,
                     bool engine_running) {
    if (!engine_running) {
        f->flatline_asserted = false;
        f->last_value = current_value;
        f->stable_since_ms = now_ms;
        return false;
    }

    float change = fabsf(current_value - f->last_value);
    float denom = fabsf(f->last_value);
    float rel = (denom > 0.1f) ? (change / denom) : change;

    if (rel > 0.01f) {
        f->last_value = current_value;
        f->stable_since_ms = now_ms;
        f->flatline_asserted = false;
    } else {
        if (now_ms - f->stable_since_ms >= FLATLINE_TIMEOUT_MS) {
            f->flatline_asserted = true;
        }
    }
    return f->flatline_asserted;
}

bool channel_health_update(ChannelHealth* ch, int raw_adc, float eng_value,
                           bool (*plausibility_fn)(float),
                           FlatlineState* flat, unsigned long now_ms,
                           bool engine_running) {
    bool elec_bad = electrical_fault(raw_adc);
    bool plaus_bad = !plausibility_fn(eng_value);
    bool flat_bad = flatline_update(flat, eng_value, now_ms, engine_running);

    bool elec_asserted = debounce_update(&ch->electrical_db, elec_bad);
    bool plaus_asserted = debounce_update(&ch->plausibility_db, plaus_bad);
    ch->flatline = flat_bad;

    bool healthy = !elec_asserted && !plaus_asserted && !flat_bad;
    if (healthy) {
        ch->age_ticks = 0;
        ch->tick_subdiv = 0;
    } else {
        ch->tick_subdiv++;
        if (ch->tick_subdiv >= 10) {
            ch->tick_subdiv = 0;
            if (ch->age_ticks < 255) ch->age_ticks++;
        }
    }
    return healthy;
}

bool plausibility_oil_temp_F(float v) {
    return v >= -40.0f && v <= 350.0f;
}

bool plausibility_pressure_psi(float v) {
    return v >= -5.0f && v <= 200.0f;
}

bool plausibility_kpa(float v) {
    return v >= 0.0f && v <= 200.0f;
}
