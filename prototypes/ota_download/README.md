# OTA Download — Path β verification

**Date:** 2026-05-04
**Status:** ✅ Verified end-to-end

## What this is

The canonical Arduino OTA example sketch (lifted unmodified from the
Renesas core's `OTAUpdate/examples/OTA/`), used as a sanity check that
the vendor-blessed download-then-update path works on our hardware.

This is **not** production code. The production maintenance-mode design
in Phase J adapts this pattern with our own state machine, BLE-driven URL
input, and LED-matrix progress display.

## Test result (2026-05-04)

Configuration:
- R4 WiFi connected as STA to phone hotspot "MEES" at -43 dBm
- WiFi firmware 0.6.0
- `OTA_FILE_LOCATION` = `https://downloads.arduino.cc/ota/UNOR4WIFI_Animation.ota`

Outcome:
1. `WiFi.begin()` connected, IP `10.113.172.177` assigned
2. `ota.begin("/update.bin")` returned 0 (OK)
3. `ota.setCACert(root_ca)` returned 0 (OK)
4. `ota.download(URL, "/update.bin")` ran for ~3 minutes downloading at
   ~1 KB/s through the hotspot. **Note:** during download, ESP32 native
   logs leak to Serial as incrementing byte counters and occasional
   `E (...) uart: uart_get_buffered_data_len(...): uart driver error`
   messages. These are the modem firmware's verbose download progress and
   are not actionable.
5. `ota.verify()` and `ota.update("/update.bin")` ran without printed errors
6. R4 rebooted into the Arduino LED-matrix animation firmware — visible
   on the onboard LED matrix as the canonical "wait for the invasion"
   animation
7. We re-flashed `carduino-v4` via USB to roll back to production

## Why this is the architecture for Phase J

- Bypasses the broken `WIFI_FILE_APPEND` modem firmware bug we found in
  `prototypes/ota_proto/`
- Uses the well-tested vendor library happy path — fewer surprises
- No need to host an HTTP server on the R4 → simpler maintenance-mode state
  machine, less RAM pressure
- Matches the user's "from the driveway" requirement: phone hotspot
  provides internet, R4 pulls firmware from a public URL (e.g. a GitHub
  release asset)

## What's TBD for production

- **CA cert source.** The Arduino example bundles `root_ca.h` containing
  CA certs valid for `downloads.arduino.cc`. For our chosen host
  (`raw.githubusercontent.com` or `objects.githubusercontent.com`) we
  need that host's chain. Strategy: extract via
  `openssl s_client -connect raw.githubusercontent.com:443 -showcerts`
  and embed the root CA only.
- **HTTP redirect handling.** GitHub release-asset URLs (`/releases/download/...`)
  redirect to `objects.githubusercontent.com`. Whether `OTAUpdate::download()`
  follows redirects is undocumented — needs bench verification in the
  GitHub-hosted test (which is the next step after this prototype).
- **`.ota` file generation.** Arduino does not document how a regular
  sketch `.bin` is wrapped into the `.ota` format the modem expects. The
  conversion tool likely lives in the
  [`arduino/uno-r4-wifi-usb-bridge`](https://github.com/arduino/uno-r4-wifi-usb-bridge)
  repo. Needs investigation in Phase J planning.
- **WiFi credentials storage.** Phase J needs to decide: hardcoded in
  `secrets.h` (simple, requires re-flash to change SSID), persisted in
  EEPROM via BLE command (flexible, more complexity). Lean toward EEPROM
  since the user can change phone hotspots.
- **URL provisioning.** Likely as a BLE command parameter:
  `ota https://github.com/.../firmware.ota`.
