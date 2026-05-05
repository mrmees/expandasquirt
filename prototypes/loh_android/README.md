# LOH + OkHttp / R4 OTA bench prototype (Tasks 53/54)

This throwaway Android prototype has two independent test paths.

**Test path A: LOH + echo server (Phase L Task 54)** verifies on Samsung S25+ / Android 16 / One UI 8 that:

1. `WifiManager.startLocalOnlyHotspot()` actually returns success and reveals the auto-generated SSID/passphrase
2. `ConnectivityManager.NetworkCallback` fires with the LOH `Network` object within a reasonable window
3. An `OkHttpClient` bound to that Network's `socketFactory` actually routes through the LOH AP rather than cellular
4. A POST to a laptop joined to the LOH succeeds end-to-end

**Test path B: push .bin to R4 via mobile hotspot (Phase L Task 53)** verifies that the Android app can push a compiled firmware `.bin` directly to the JAndrassy/ArduinoOTA listener on an Uno R4 WiFi using plain OkHttp over the phone hotspot/default network.

If both paths pass, the production v4.x OTA design is unblocked. If either fails, record the failed path and revise that part of the design.

## What's in this directory

| File | Purpose |
|---|---|
| `MainActivity.kt` | Kotlin/Compose source for the throwaway prototype app |
| `AndroidManifest-snippet.xml` | Manifest permissions to merge into the project |
| `build.gradle.kts-deps.txt` | Gradle dependencies to add (OkHttp + coroutines) |
| `echo-server.py` | Tiny Python HTTP echo server for the laptop side |
| `README.md` | This file — setup steps + results template |
| `notes-results.md` | (Created after testing) verdict + observations |

## Setup

### Phone side (one-time per project)

1. **Open Android Studio.** Create a new project: **Empty Activity (Compose)**.
   - Name: `Carduino LOH Proto` (or whatever)
   - Package: `works.mees.carduino.protoloh`
   - Minimum SDK: **API 26** (Android 8.0)
   - Target/Compile SDK: **API 35** (Android 16) or whatever's current
   - Build configuration: **Kotlin DSL** (`.kts`)
   - Save location: doesn't matter — this project is throwaway and is **not** committed to the repo

2. **Replace `MainActivity.kt`** with the contents of this directory's `MainActivity.kt`. Adjust the package declaration if your AS project used a different package.

3. **Merge the `<uses-permission>` lines** from `AndroidManifest-snippet.xml` into `app/src/main/AndroidManifest.xml`.

4. **Add the dependencies** from `build.gradle.kts-deps.txt` to `app/build.gradle.kts`'s `dependencies { ... }` block. Sync Gradle.

5. **Build + install on the S25+** (USB debugging enabled, plugged into MINIMEES):
   ```bash
   cd <android-studio-project-dir>
   ./gradlew installDebug
   ```

### Laptop side (each test run)

1. **Run the echo server** (Python 3 required):
   ```bash
   python3 prototypes/loh_android/echo-server.py
   ```
   Note the IP addresses it prints. You won't know which one is on the LOH yet; that's fine — you'll figure it out after step 4 below.

## Test path A: LOH + echo server (Phase L Task 54)

1. **Phone:** open the prototype app. The app's UI shows the current `LAPTOP_IP` constant — initially `192.168.49.2`, which is a guess.

2. **Phone:** tap **Run LOH + echo test**. The app:
   - Registers a `NetworkCallback` filtered for `TRANSPORT_WIFI` + `!NET_CAPABILITY_INTERNET`
   - Calls `WifiManager.startLocalOnlyHotspot()`
   - Logs the SSID + passphrase on screen

3. **Laptop:** look at the SSID + passphrase from the phone. **Connect the laptop's WiFi** to that SSID using the displayed passphrase. Wait ~5 sec for DHCP.

4. **Laptop:** check the new IP your laptop got from the LOH. On Linux/macOS:
   ```bash
   ifconfig wlan0   # or ip addr show wlan0
   ```
   On Windows: `ipconfig`. Look for the WiFi adapter — its IP will be on the LOH subnet (typically `192.168.49.X` on Android, but OEM-dependent).

5. **Compare** the laptop's LOH IP to `LAPTOP_IP` in `MainActivity.kt`. If they differ:
   - Stop the app (back out, force-stop)
   - Update `LAPTOP_IP` in MainActivity.kt
   - Re-run `./gradlew installDebug`
   - Tap **Run LOH + echo test** again

6. **Watch both sides:**
   - The Android app's status panel should walk through the four phases and end with `HTTP 200: OK 51` (or similar — the byte count of the test payload)
   - The laptop's `echo-server.py` should print the received POST with the test payload bytes

7. **If anything fails**, capture the exact failure message and which phase it failed at.

### Pass criteria

- `[1b/4]` reaches `LOH onStarted` (no `onFailed`)
- `[2/4]` reads non-empty SSID + passphrase
- `[3/4]` `NetworkCallback.onAvailable` fires within 10 sec of LOH start
- `[4/4]` POST returns `HTTP 200: OK <bytes>` with the actual byte count of the payload
- `echo-server.py` prints the same payload bytes
- The `Authorization: Basic` header (not present in this test, but the Content-Type and Content-Length headers should match the body)

## Common failure modes to record

- **LOH `onFailed(reason=...)`** — record the reason code:
  - `0` ERROR_NO_CHANNEL — band unavailable
  - `1` ERROR_GENERIC — OEM-specific failure
  - `2` ERROR_INCOMPATIBLE_MODE — already tethering or some other conflict
  - `3` ERROR_TETHERING_DISALLOWED — carrier or admin blocked
- **NetworkCallback never fires** — design assumption broken; need to revisit
- **HTTP error/timeout** — `LAPTOP_IP` wrong, or the `socketFactory` binding didn't actually scope to LOH (in which case the request might go out cellular and fail with a route-to-host error, or hit a wrong host on the home network)

## Test path B: push .bin to R4 via mobile hotspot (Phase L Task 53)

### Prereqs

1. **Pixel 8:** enable the regular Android mobile hotspot. This path does not use LocalOnlyHotspot.
2. **R4:** flash `prototypes/ota_arduinoota/jandrassy_proto/jandrassy_proto.ino` over USB with `arduino_secrets.h` set to the Pixel hotspot SSID/passphrase.
3. **R4:** open USB serial after boot and wait for `Connected. Local IP: ...`. Record that DHCP IP.
4. **Android source:** update the `R4_IP` constant in `MainActivity.kt` to the serial-reported IP. Leave `R4_PORT = 65280` and password `testpw` unless the firmware sketch changed.
5. **Payload:** compile `prototypes/ota_arduinoota/test-sketch/test-sketch.ino` so the file picker can access `prototypes/ota_arduinoota/test-sketch/build/test-sketch.ino.bin`.

### Steps

1. Build + install the Android app on the Pixel 8.
2. Confirm the Pixel hotspot is still enabled and the R4 serial monitor still shows the ArduinoOTA listener alive.
3. Open the prototype app and tap **Push .bin to R4**.
4. In the SAF file picker, select `prototypes/ota_arduinoota/test-sketch/build/test-sketch.ino.bin`.
5. Watch the Android status panel. It should show file size, POST start, progress, and the HTTP response code/body.
6. Re-open the R4 USB serial monitor after the response. A successful push reboots into `test-sketch.ino`.

### Expected result

- Android reports `HTTP 200: OK`.
- R4 reboots within roughly 10 seconds.
- USB serial monitor shows repeated `HELLO_FROM_OTA_TARGET`.

### Pass criteria

- R4 joined the Pixel hotspot and printed a DHCP IP on USB serial.
- `R4_IP` in `MainActivity.kt` matches that DHCP IP.
- Android file picker opens and the selected `.bin` size is logged.
- POST goes to `http://<R4_IP>:65280/sketch` with Basic auth `arduino:testpw`.
- Android receives `HTTP 200: OK`.
- R4 reboots into `test-sketch.ino` and prints `HELLO_FROM_OTA_TARGET`.

### Fail criteria / record

- File picker cannot access the `.bin` file.
- Android reports `401`, `413`, `414`, `500`, timeout, or connection failure.
- R4 does not reboot after `HTTP 200: OK`.
- R4 reboots but does not print `HELLO_FROM_OTA_TARGET`.

## Results template (copy to `notes-results.md` after running)

```markdown
# LOH + OkHttp routing — bench results

**Date:** YYYY-MM-DD
**Phone:** Samsung S25+ / Android 16 / One UI X.X
**Laptop:** [model + OS]

## Phase 1 — LOH start
- Reached onStarted: yes / no  ← reason if no:

## Phase 2 — SoftAp config
- SSID format: AndroidShare_XXXX (record actual)
- Passphrase length: N chars

## Phase 3 — NetworkCallback fires
- Latency from LOH onStarted to onAvailable: N seconds
- network.toString(): ...
- Capabilities: ...
- Did `removeCapability(NET_CAPABILITY_INTERNET)` filter work, or did the LOH come back with INTERNET set anyway?

## Phase 4 — Routed POST
- LAPTOP_IP used: ...
- HTTP response: ...
- echo-server.py received the bytes: yes / no
- Total elapsed time from "Run LOH + echo test" tap to result: N seconds

## Test path B — R4 OTA push
- R4_IP used: ...
- File selected: ...
- File size: N bytes
- HTTP response: ...
- Rebooted into test-sketch: yes / no
- Serial showed HELLO_FROM_OTA_TARGET: yes / no

## Verdict
- ☐ PASS — production design unblocked
- ☐ FAIL — [describe what to revise]

## Observations / quirks
- [anything OEM-specific worth knowing]
```
