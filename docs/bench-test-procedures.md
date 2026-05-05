# CARDUINO v4 Bench Test Procedures

These procedures expand the Phase 1 bench tests from `DESIGN.md` section 8.1.
Test IDs and titles are copied from that section. Numeric constants come from
`DESIGN.md` or `carduino-v4/config.h`; when the source does not define a
numeric threshold, this document says so.

## Common Setup

- CARDUINO v4 hardware with Freenove FNK0096 / Uno R4 WiFi clone.
- Keyestudio EF02037 MCP2515 CAN-Bus Shield seated on the R4 headers.
- Switched 12V bench supply feeding the buck-boost regulator, then the R4 VIN
  through the 1A polyfuse.
- Shared ground between bench supply, R4, sensors, and CAN tooling.
- USB cable connected to the R4 for serial output and firmware upload.
- USB-CAN dongle connected to the CAN_H/CAN_L pair for CAN inspection.
- Potentiometer or decade box for ADC input injection.
- Thermistor test setup for ice bath, room temperature, and boiling water.

Build before bench work:

```powershell
& "C:\Program Files\Arduino CLI\arduino-cli.exe" compile --fqbn arduino:renesas_uno:unor4wifi carduino-v4/
```

Host-side unit tests before bench work:

```powershell
& "C:\Program Files\Git\bin\bash.exe" tests/run-tests.sh
```

## 1.1 Smoke test

Purpose: verify first power-up behavior before exercising firmware features.

Setup:
- Inspect VIN, 5V, GND, sensor 5V, CAN_H, and CAN_L wiring against
  `docs/wiring-diagram.md`.
- Set the bench supply for switched 12V into the buck-boost input.
- Put a current meter in series with the buck-boost input or supply output.

Steps:
1. Power the bench supply with the R4 disconnected from VIN; verify the
   buck-boost output is regulated 12V.
2. Power down, connect the R4 VIN path through the 1A polyfuse, then power up.
3. Watch for smoke, odor, abnormal heat, or the bench supply entering current
   limit.
4. Record idle current after boot reaches a stable state.
5. Confirm USB serial prints the boot banner.

Pass criteria:
- No smoke, odor, abnormal heating, or current-limit event.
- Buck-boost output remains regulated at 12V.
- R4 boots and prints `CARDUINO v4 booting...`.
- Idle current is measured and recorded. `DESIGN.md` section 8.1 requires an
  expected idle-current check, but no numeric expected current is specified in
  the design or code.

## 1.2 Boot self-test verification

Purpose: verify boot self-test error display behavior.

Source behavior:
- `ERR01` is ADC self-test failure and halts.
- `ERR02` is CAN init or loopback failure and continues degraded.
- `ERR03` is BLE init failure and continues degraded.

Setup:
- CARDUINO powered on the bench.
- USB serial connected at 115200 baud.
- LED matrix visible.

Steps:
1. Boot normally with the CAN shield seated and connected.
2. Verify serial output reaches the boot banner and no forced error is shown.
3. Force the CAN self-test to fail by removing the MCP2515 shield or otherwise
   preventing MCP2515 initialization, then reboot.
4. Observe serial output and LED matrix.
5. Force BLE initialization failure only if a repeatable bench method is
   available without modifying unrelated firmware behavior.
6. Force ADC self-test failure only with an explicit temporary test build; the
   current `adc_self_test()` implementation is a pass-through placeholder.

Pass criteria:
- Normal boot reaches `DISP_NORMAL` when no boot error is present.
- Forced CAN failure prints `ERR02 CAN init failed` or an MCP2515 init failure
  message, displays error 2, and continues degraded without CAN transmit.
- Forced BLE failure displays error 3 and continues degraded.
- Forced ADC failure displays error 1 and halts.
- Any forced-error test build is reverted before continuing to the next bench
  test.

## 1.3 Sensor pipeline (pot injection)

Purpose: sweep ADC channels and verify raw tracking, EWMA filtering,
conversion, and fault detection.

Setup:
- Potentiometer wired as a 0-5V signal source sharing CARDUINO 5V and GND.
- 100 nF ADC noise cap remains installed on the channel under test.
- USB serial connected for the temporary 500 ms sensor printout.

Steps:
1. Start with A0 (`PIN_OIL_TEMP`) and sweep the potentiometer slowly across the
   input range.
2. Observe `oilT` on USB serial and confirm the value changes smoothly after
   EWMA filtering.
3. Repeat the sweep for A1 (`PIN_POST_SC_TEMP`), A2 (`PIN_OIL_PRESS`), A3
   (`PIN_FUEL_PRESS`), and A4 (`PIN_PRE_SC_PRESS`).
4. For A2 and A3, verify 0-5V maps across the 0-100 PSI configured full scale.
5. For A4, verify the Bosch transfer function path produces kPa readings using
   `BOSCH_SLOPE` and `BOSCH_OFFSET`.
6. Drive each channel to electrical-fault conditions near ADC minimum and
   maximum and verify the channel health bit clears after debounce.

Pass criteria:
- Every configured ADC channel responds to input changes on its assigned pin.
- EWMA output changes smoothly, with no jumps to unrelated channels.
- A2 and A3 report pressure according to their 0-100 PSI full-scale constants.
- A4 reports pressure using the Bosch 0 261 230 146 conversion constants.
- Fault detection asserts only after `HEALTH_DEBOUNCE_BAD` bad samples and
  clears only after `HEALTH_DEBOUNCE_GOOD` good samples.

## 1.4 Thermistor curve verification

Purpose: verify the two thermistor conversion paths against physical
temperature points.

Setup:
- Oil temperature thermistor path on A0 with 10k ohm pull-up to 5V.
- Post-supercharger temperature thermistor path on A1 with 2.49k ohm pull-up to
  5V.
- Ice bath, room-temperature reference, and boiling-water reference.

Steps:
1. Place the A0 thermistor in the ice bath and wait for the reading to settle.
2. Record the reported oil temperature.
3. Repeat at room temperature and boiling water.
4. Repeat the same three-point check for the A1 GM open-element NTC path.

Pass criteria:
- Each point is within +/-2 deg F of the reference temperature, as required by
  `DESIGN.md` section 8.1.
- A0 uses `OIL_TEMP_PULLUP_OHMS`, `OIL_TEMP_R25`, and `OIL_TEMP_BETA`.
- A1 uses `POST_SC_TEMP_PULLUP_OHMS`, `POST_SC_TEMP_R25`, and
  `POST_SC_TEMP_BETA`.

## 1.5 CAN frame inspection

Purpose: verify transmitted CAN frames match the wire format in `DESIGN.md`
section 5.3 and `can_protocol.cpp`.

Setup:
- USB-CAN dongle on the same CAN_H/CAN_L pair as the MCP2515 shield.
- CAN bus set to 500 kbps.
- Valid sensor inputs or stable injected values on A0-A4.

Steps:
1. Power CARDUINO and start the CAN capture.
2. Capture frame ID 1025 (`0x401`) and frame ID 1026 (`0x402`).
3. Verify both frames are 8 bytes and repeat at 10 Hz.
4. Decode Frame 1:
   - bytes 0-1: oil temperature, deg F x 10, big-endian
   - bytes 2-3: oil pressure, PSI x 10, big-endian
   - bytes 4-5: fuel pressure, PSI x 10, big-endian
   - bytes 6-7: pre-supercharger pressure, kPa x 10, big-endian
5. Decode Frame 2:
   - bytes 0-1: post-supercharger air temperature, deg F x 10, big-endian
   - bytes 2-3: `0xFFFF`
   - byte 4: sequence counter
   - byte 5: health bitmask
   - byte 6: status flags
   - byte 7: max held-value age in 100 ms units
6. Inject an MS3-style RPM frame on ID 1512 (`0x5E8`) with RPM in bytes 2-3,
   then verify the firmware accepts that receive frame without processing
   unrelated CAN IDs.

Pass criteria:
- IDs 1025 and 1026 are present at 10 Hz.
- Every transmitted byte matches the frame layout above.
- Frame 2 byte 4 increments and wraps as an 8-bit sequence counter.
- Frame 2 byte 5 matches the health state for the five sensor channels.
- The MCP2515 receive path admits ID 1512 for RPM gating.

## 1.6 Watchdog

Purpose: verify the firmware resets after a super-loop stall.

Setup:
- USB serial connected.
- Temporary test build that injects an artificial loop delay longer than
  `WATCHDOG_NORMAL_MS` (1000 ms).

Steps:
1. Flash the temporary watchdog test build.
2. Let the firmware boot normally.
3. Trigger or wait for the artificial delay.
4. Observe serial output and reboot behavior.
5. Restore the normal firmware after the test.

Pass criteria:
- Firmware prints `WATCHDOG TIMEOUT - resetting`.
- R4 resets and boots again.
- Persistent boot counter increments after the reset.
- Reset cause may read `RESET_UNKNOWN`; `persistent.cpp` documents this as a
  known RA4M1 / Arduino Renesas core limitation.

## 1.7 Maintenance mode + OTA

Status: DEFERRED to v4.x.

Wireless firmware update flow is not part of v4. `DESIGN.md` section 6.4 states
that v4 ships with USB-cable updates only, so this bench test is not run for
the v4 release.

Pass criteria:
- Not applicable for v4.

## 1.8 Persistent state

Purpose: verify persistent boot state survives resets.

Setup:
- USB serial connected.
- Normal firmware build.

Steps:
1. Boot CARDUINO and record the printed boot counter and reset cause.
2. Reboot the board.
3. Confirm the boot counter increments.
4. Set a fatal error through firmware code only if an existing test hook is
   available; otherwise leave `last_fatal_err` unchanged.
5. Power-cycle and confirm the persistent structure remains valid.

Pass criteria:
- EEPROM state initializes with magic `0xCAFEBABE`.
- Boot counter increments across resets.
- Persistent state CRC remains valid after reboot.
- Reset cause is recorded best-effort; `RESET_UNKNOWN` is acceptable under the
  known limitation documented in `persistent.cpp`.
