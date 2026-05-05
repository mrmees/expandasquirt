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

## What we learned that changed the architecture

After verifying the arduino.cc round-trip we tried to repeat the test using
a `.ota` file we re-hosted on a GitHub release. That second test failed in
two distinct ways before we abandoned the approach:

1. **No HTTP redirect support.** `Arduino_ESP32_OTA::startDownload()` does a
   single GET to the original URL and rejects anything that isn't HTTP 200
   (cited line: `Arduino_ESP32_OTA.cpp#L96-L133`). GitHub release-asset URLs
   serve HTTP 302 redirects to `objects.githubusercontent.com`, so they
   never work — only direct (no-redirect) URLs like `raw.githubusercontent.com/...`
   are usable.
2. **Hardcoded 60s host-side timeout.** `EXTENDED_MODEM_TIMEOUT = 60000` ms
   in `WiFiS3/Modem.h` is the deadline for `download()` (and even the
   "non-blocking" `startDownload()` — it also waits for an OK from the
   bridge before returning). On a phone hotspot at ~1 KB/s with RSA-4096
   ISRG Root X1 cert validation, the TLS handshake + transfer can easily
   exceed 60 s. Symptom: `download()` returns `-26` (`Error::Modem`) even
   though the bridge eventually succeeds in the background. The host has
   already given up by then.
3. **`.ota` container is not produced by `arduino-cli`.** The format is
   LZSS-compressed payload + 20-byte Arduino OTA header (length, CRC32,
   magic `0x23411002`, HeaderVersion). Generating one from a sketch `.bin`
   requires running `lzss.py` and then `bin2ota.py UNOR4WIFI` from
   `arduino-libraries/ArduinoIoTCloud/extras/tools` — a separate manual
   step per release. Native Windows isn't documented (the helper ships
   `lzss.so`/`lzss.dylib` only). See `notes-ota-format.md` for the full
   recipe.

Combined, these issues made the "device pulls from URL" pattern more
friction than it's worth.

## Decision (2026-05-04): defer wireless OTA

v4 ships with USB-cable firmware updates. The path forward — when we pick
this up again — is the third-party
[`JAndrassy/ArduinoOTA`](https://github.com/JAndrassy/ArduinoOTA) library
which uses a "PC pushes to device" model (TCP listener on port 65280,
Arduino's `arduinoOTA` binary as the upload tool, raw `.bin`, no certs, no
60s timeout). That requires a companion app or `arduino-cli`-on-phone
(Termux) — see `DESIGN.md` §6.4.3 for the v4.x roadmap.

The contents of this directory (`ota_download.ino`, `root_ca.h`,
`arduino_secrets.h`) are kept as reference for what *did* work
(arduino.cc round-trip via `OTAUpdate::download()`) and what *didn't*
(GitHub URL with the same code). Do not use the sketch as-is for production
— it's research evidence, not a starting point.
