# CARDUINO v4

Sensor adapter for MS3Pro PNP on a 2000 NB1 Miata. See `DESIGN.md` for the full spec.

## Quick start
TBD — populated when implementation lands.

## Build
```bash
arduino-cli compile --fqbn arduino:renesas_uno:unor4wifi carduino-v4/
```

## Flash
```bash
arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port <PORT> carduino-v4/
```

## Toolchain

Required:
- `arduino-cli` >= 1.0
- Arduino Renesas UNO board package (see `libraries.txt` for version)
- Libraries listed in `libraries.txt`
- ESP32-S3 connectivity firmware on the R4 >= v0.6.0

To set up a fresh dev machine: see `docs/setup.md`.
