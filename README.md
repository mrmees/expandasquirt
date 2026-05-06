# EXPANDASQUIRT v4

EXPANDASQUIRT v4 is a sensor adapter for an MS3Pro PNP on a 2000 NB1 Miata. It reads
five aftermarket analog sensors on an Uno R4 WiFi-class board, converts them to
engineering units, and broadcasts them to MS3 over CAN at 10 Hz.

## Hardware

- Freenove FNK0096 / Uno R4 WiFi clone.
- Keyestudio EF02037 MCP2515 CAN-Bus Shield with 16 MHz crystal.
- Switched 12V input through a buck-boost regulator, 1A polyfuse, and R4 VIN.
- Five sensor inputs:
  - Oil temperature, NTC thermistor on A0.
  - Post-supercharger air temperature, GM open-element NTC on A1.
  - Oil pressure, 0-5V ratiometric 0-100 PSI sensor on A2.
  - Fuel pressure, 0-5V ratiometric 0-100 PSI sensor on A3.
  - Pre-supercharger pressure, Bosch 0 261 230 146 MAP sensor on A4.

See [docs/wiring-diagram.md](docs/wiring-diagram.md) for power, sensor, and CAN
wiring details.

## Build

Plain build (FIRMWARE_BUILD = "unknown"):
```powershell
& "C:\Program Files\Arduino CLI\arduino-cli.exe" compile --fqbn arduino:renesas_uno:unor4wifi expandasquirt-v4/
```

Build with git-sha stamp (recommended for any build that may be OTA-pushed —
the BLE banner exposes `FIRMWARE_BUILD` so the v4.x companion app can verify
which firmware is running post-update; per V4X-DESIGN.md §5.2):
```bash
GIT_SHA=$(git rev-parse --short HEAD)
"/c/Program Files/Arduino CLI/arduino-cli.exe" compile \
    --fqbn arduino:renesas_uno:unor4wifi \
    --build-property "build.extra_flags=-DGIT_SHORT_SHA=$GIT_SHA" \
    expandasquirt-v4/
```

Pinned board and library versions are listed in [libraries.txt](libraries.txt).

## Flash

v4 firmware can be updated either over USB or wirelessly via the v4.x companion app.

**USB:**

```powershell
& "C:\Program Files\Arduino CLI\arduino-cli.exe" upload --fqbn arduino:renesas_uno:unor4wifi --port COM<N> expandasquirt-v4/
```

Replace `COM<N>` with the R4 serial port.

**Wireless (v4.x companion app):** The Android app pushes a `.bin` over the
user's phone hotspot via the JAndrassy/ArduinoOTA library (1.1.1). See
`V4X-DESIGN.md` for the design and the **v4.x companion Android app** section
below for setup.

## v4.x companion Android app

Live BLE sensor dashboard + diagnostic actions + wireless OTA wizard. Source
under `app/`. The app is sideloaded — there's no Play Store distribution.

**Build the debug APK:**
```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Install on a paired Android phone:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n works.mees.expandasquirt/.MainActivity
```

**Features:**
- Live BLE dashboard for all five sensors with health indicators
- Diagnostic actions: status dump, boot info, help, reboot, USB rescue
- Wireless firmware updates via a manual-hotspot OTA wizard (user toggles their
  phone hotspot configured as WPA2-Personal; the app pushes the `.bin` over it
  via mDNS-resolved HTTP). The R4 modem firmware 0.6.0 cannot reliably join
  WPA3 / WPA2-WPA3-Transition APs, so the hotspot must be WPA2-Personal.
- Hotspot credentials are persisted in DataStore; first-run can import them
  from a Wi-Fi QR screenshot.

**Min SDK 26 (Android 8.0). Target SDK 36 (Android 16).**

## Tests

Run host-side unit tests:

```powershell
& "C:\Program Files\Git\bin\bash.exe" tests/run-tests.sh
```

Bench-side validation is documented in
[docs/bench-test-procedures.md](docs/bench-test-procedures.md).

## Documentation

- [DESIGN.md](DESIGN.md) - architecture, hardware, CAN protocol, firmware
  behavior, and validation plan.
- [V4X-DESIGN.md](V4X-DESIGN.md) - companion Android app + maintenance-mode
  design (supersedes DESIGN.md §6.4.3).
- [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) - task-by-task build plan
  (Phases A-K cover v4; Phases L-P cover v4.x companion app + OTA).
- [docs/wiring-diagram.md](docs/wiring-diagram.md) - power tree, pinout,
  sensor wiring, ADC filtering, and CAN shield notes.
- [docs/tunerstudio-setup.md](docs/tunerstudio-setup.md) - MS3/TunerStudio CAN
  Receiving and Generic Sensor Input setup.
- [docs/bench-test-procedures.md](docs/bench-test-procedures.md) - Phase 1
  bench test setup, steps, and pass criteria.

## License

No license file is present in this repository.
