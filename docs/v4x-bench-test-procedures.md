# CARDUINO v4.x OTA Bench Test Procedures

End-to-end bench validation of the v4.x OTA wizard (`maintenance` BLE
command → manual phone hotspot → mDNS → HTTP upload to JAndrassy
ArduinoOTA listener → flash + soft reset → BLE reconnect + version
verify). Architecture in `V4X-DESIGN.md`; task spec in
`IMPLEMENTATION-PLAN.md` Task 77.

## Setup

- CARDUINO v4 hardware (R4 WiFi) with the v4.1.0 baseline sketch loaded.
- Phone with the carduino companion app installed (debug build is fine),
  paired to the Carduino over BLE on the dashboard screen.
- Phone Wi-Fi hotspot configured but **off** at the start of each cycle.
  Note the SSID and password — these are the inputs to the wizard.
- Phone has a `.bin` ready in `Download/` from a recent
  `arduino-cli compile --output-dir <dir> carduino-v4/` run. Bumping
  `FIRMWARE_VERSION` in `config.h` between cycles makes the dashboard
  banner check after reconnect a real verification.
- USB cable from R4 to the dev workstation for serial diagnostics.
  Capture serial at 115200 with DTR asserted (the `.tmp-serial-capture.py`
  scratch script in the repo root works; `arduino-cli monitor` does not
  reliably read R4 USB CDC in headless mode).

Build before bench work:

```powershell
& "C:\Program Files\Arduino CLI\arduino-cli.exe" compile --fqbn arduino:renesas_uno:unor4wifi --output-dir .tmp-ota-build carduino-v4/
```

## Cycle Procedure

1. Confirm dashboard is BLE-connected and `seq=` counter is climbing.
2. Tap **menu → Firmware update**. Pick the prepared `.bin`.
3. Confirm pre-flight (size sanity-check passes).
4. **Turn on the phone hotspot.** Enter SSID + password into the wizard.
5. Tap continue. The wizard sends `maintenance ssid=… psk=… pwd=…` over
   BLE, waits for `OK maintenance armed`, waits up to 8 sec for BLE drop,
   then ~5 sec for the R4 to join the hotspot, then mDNS-discovers it.
6. HTTP upload runs to ~100% — wizard shows progress.
7. JAndrassy `apply()` flashes and triggers `NVIC_SystemReset`. R4 reboots
   into normal mode running the new sketch.
8. App reconnects BLE, reads the version banner, shows **Update complete**.

## Measured Timing (one successful cycle, 2026-05-05)

Captured via Serial diagnostic prints (`MM_TRACE` flag in
`maintenance_mode.cpp`, off by default; flip to `1` to re-enable for
bench debug).

| Phase | Duration | Notes |
|---|---|---|
| `OK maintenance armed` reply | ~50 ms | within BLE write of cmd |
| ARMED → BLE_DRAIN | 3004 ms | spec: 3000 ms ± loop slop |
| BLE_DRAIN → WIFI_JOINING | 1005 ms | spec: 1000 ms ± loop slop |
| WiFi.begin → WL_CONNECTED | ~170 ms | depends on hotspot signal |
| WIFI_JOINING → OTA_READY | ~654 ms total | including TCP listener up |
| HTTP upload (110 KB) | a few seconds | varies with phone Wi-Fi |
| `apply()` flash + reset | included in ~14 sec total | from upload-100% to "booting" |
| Reboot to BLE-back-up | ~1 sec post-boot | sensor prints resume immediately |
| Total wizard wallclock | ~25–30 sec | acceptable for a one-shot op |

Timeouts in `OtaViewModel.runOtaFlow()` all have comfortable margin against
these measurements (8 s for BLE drop, 15 s for mDNS, 30 s for post-apply
BLE reconnect, 5 s for version banner). Task 75 (timing tune) intentionally
left conservative — see V4X-DESIGN.md.

## Failure Modes Encountered (and Fixed) During Bench Bring-Up

The first run of this procedure surfaced two bugs. Both are fixed in the
current head; they are documented here so a future regression has a
fingerprint.

### `maintenance_tick` time underflow on entry from BLE handler

**Symptom:** wizard fails at "Carduino didn't drop BLE in time" within
8 seconds of `OK maintenance armed`. Dashboard `seq=` counter keeps
climbing — the firmware is fine, never dropped BLE, never advanced past
ARMED. With `MM_TRACE` on the smoking gun is:

```
[mm] -> 1 (ARMED)    at ms=40099
[mm] -> 7 (ABORTING) at ms=40064   ← lower than ARMED
```

**Cause:** the loop captured `now = millis()` at top of iteration; then
`BleServicePhase()` ran chunked BLE writes for "OK maintenance armed"
which advanced millis by 30–50 ms; `maintenance_request_enter` set
`s_state_entered_ms` from a *fresh* millis() (so it was ahead of `now`);
then `maintenance_tick(now)` computed `elapsed = now - s_state_entered_ms`
and underflowed the unsigned subtraction to ~4 billion → matched the
`elapsed >= 10000` branch → ABORTING → NORMAL inside one loop pass.

**Fix:** drop the `now` parameter from `maintenance_tick()` and
`to_state()`; both functions now compute `millis()` internally so
timestamps are always monotonic with `s_state_entered_ms`. Commit
`dd5a4ff`.

### Cleartext HTTP blocked by Android Network Security default

**Symptom:** wizard advances cleanly through the BLE handshake, R4
joins hotspot, mDNS finds it — then the upload step fails with
"CLEARTEXT communication to 10.x.x.x not permitted by network security
policy".

**Cause:** Android target API 28+ blocks plain HTTP by default. The
JAndrassy ArduinoOTA listener is HTTP, not HTTPS.

**Fix:** add `app/src/main/res/xml/network_security_config.xml` with
`<base-config cleartextTrafficPermitted="true" />` and reference it from
the manifest. App-wide cleartext is acceptable here because the app's
only HTTP destination is the R4 OTA endpoint on the phone-hosted
hotspot — narrow scope, intentional. Commit `741feab`.

## Status of the 5-Cycle Validation

Task 77 spec is 5 sequential successful cycles. As of 2026-05-05 we have
one confirmed end-to-end success. The remaining 4 cycles should be run
once the implementation is stable for a stretch, to confirm reliability
under repetition (no firmware corruption, BLE state machine doesn't get
wedged across multiple OTA passes, hotspot re-join works after the R4 has
re-booted, etc.).

When running the 4 followup cycles, capture per-cycle:

| Cycle | Maintenance → upload-start | Upload duration | BLE-back duration | Banner version match | Result |
|---|---|---|---|---|---|
| 1 | (2026-05-05 — see "Measured Timing" above) | | | yes | OK |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |

If any cycle fails, capture serial with `MM_TRACE 1` and append to the
"Failure Modes" section above.
