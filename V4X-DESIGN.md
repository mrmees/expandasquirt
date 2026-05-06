# CARDUINO v4.x — Companion App + Maintenance Mode Design

**Status:** Draft, brainstorming output. Pre-implementation. Supersedes the deferred-to-v4.x sections of `DESIGN.md` (§6.4.3 placeholder, §6.5 maintenance LED states, §10 OTA-related open questions) and the stale Phase J in `IMPLEMENTATION-PLAN.md`.

**Date:** 2026-05-05
**Scope:** v4.x = the companion Android app + the small firmware additions needed to support wireless OTA via that app. v4 firmware is shipped and untouched here unless explicitly listed as a v4.x change.

---

## 1. Goals

1. **Replace nRF Connect for daily live-console use** once the device is in the car. Display the 5 sensor values + system meta in a compact dashboard. ~2 Hz UI refresh.
2. **Enable wireless firmware updates** without removing the device from the car or exposing the USB port. End-to-end push from the user's phone, no Termux, no `arduino-cli` on the phone.
3. **Wrap the most common diagnostic BLE commands** (Reboot, Self-test, Clear errors, View boot info, View event log) with one-tap buttons.

## 2. Non-goals

- Multi-tenant / shared device state across users. One user, one phone.
- iOS support. Android-only.
- Sensor session recording / replay. The MS3 already logs in TunerStudio.
- Two-phase OTA commit (push then confirm). Failure mode is "USB rescue." Deferred to v4.y if a real failure ever shows up.
- GitHub Releases auto-fetch. Local file picker only.
- Engine-off auto-detection. User-attestation only.
- OTA progress visualization on the LED matrix.
- Calibration helper UIs (`cal pres1 raw` / `cal therm1 raw`) — those stay USB-side at the bench.
- Verbose-mode toggling from the app.

---

## 3. System architecture

### 3.1 Components

```
┌─────────────────────┐       BLE NUS        ┌──────────────────────┐
│  Android app        │ <─────────────────>  │  CARDUINO v4 + v4.x  │
│  (Kotlin/Compose)   │                      │  firmware additions  │
│                     │                      │                      │
│  - BLE central      │ Phone hotspot        │  - Maintenance state │
│  - Live dashboard   │ (user-toggled) joins │    machine           │
│  - Diag actions     │                      │  - ArduinoOTA TCP    │
│  - OTA wizard       │  HTTP POST /sketch   │    listener (port    │
│  - Hotspot setup    │ ────────────────► :65280  65280) only during│
│                     │                      │    maintenance       │
└─────────────────────┘                      └──────────────────────┘
```

The Carduino has two transport modes: **normal** (BLE up, WiFi off, CAN/sensors live) and **maintenance** (BLE down, WiFi STA up, sensors/CAN halted, ArduinoOTA listener active). The transition is one-way during a maintenance event; recovery to normal mode is via reboot. **The transports never run concurrently** — DESIGN.md §6.6 rejected dual-stack BLE+WiFi for normal-mode reasons, and the brief transition window is structured to keep them serial as well.

**IP discovery:** since BLE is down before WiFi is up, the app can't receive the device's DHCP-assigned IP over BLE. Discovery is via **mDNS** (`carduino-v4._arduino._tcp.local`, advertised by the JAndrassy library's default mDNS responder). The Android side uses `NsdManager.discoverServices()` scoped to the phone-hotspot Network. mDNS reachability on the R4's modem firmware is one of the bench-prototype gates (§11).

### 3.2 OTA flow

```
Normal: BLE active, dashboard updates                          [user taps "Firmware update"]
                                                               [user picks .bin]
                                                               [user acks engine-off]
                                                               [user enables phone hotspot]
                                                               [app reads ssid/psk from DataStore or QR import]
1. App sends: maintenance ssid=<pct> psk=<pct> pwd=<pct>\n  (BLE)
2. Carduino:  reply OK maintenance armed timeout=3000 → MM_ARMED → 3s drain → end BLE
3. Carduino:  switch modem to STA, WiFi.begin(ssid, psk)
4. Carduino:  ArduinoOTA.begin(ip, "carduino-v4", pwd, InternalStorage)
              [JAndrassy default registers mDNS responder for _arduino._tcp.local]
5. App:       waits ~5s post-BLE-drop, then mDNS-resolves "carduino-v4._arduino._tcp.local"
              via Android NsdManager → device IP
6. App:       POST http://<ip>:65280/sketch with Basic auth, body=raw .bin
7. Carduino:  read body → InternalStorage.write() loop → 200 OK → apply()
8. Carduino:  flash erase + copy from staging + NVIC_SystemReset
9. Carduino:  boots into new firmware, BLE advertises again
10. App:      reconnects BLE, reads banner, verifies version token matches expected
```

---

## 4. Android app architecture

### 4.1 Screens

**S1. Device picker / first-run.** Shows on first launch or when no current device is set. Scan results filter to `CARDUINO-v4` advertised name. Tap one → set as current → navigate to dashboard. Includes a "rescan" affordance. Reachable later via the device-name button in the dashboard top bar.

**S2. Live dashboard.** Default screen after first connect. Layout B — compact spec list of the 5 sensors (oilT, oilP, fuelP, preP, postT) with health-dot indicator, value, and unit. Below the list: small meta strip (`seq · health 0xNN · age N.Ns`). Top bar: device nickname (tap → S1), connection state, overflow menu. Refresh cadence: ~2 Hz from the parsed BLE periodic dump (firmware sends at 5 Hz; app coalesces).

**S3. Diagnostic actions.** Reached via dashboard overflow menu. Compact list of buttons:
- Reboot device
- Run self-test
- Clear errors
- View boot info (shows last reset cause, boot counter, last fatal err)
- View event log (RAM ring buffer, ~64 events; raw text dump)

Each wraps the corresponding existing BLE command. Output is shown as monospace text. No structured filtering / search / persistence of log contents.

**S4. Firmware update wizard.** Reached via dashboard overflow menu. Multi-step:

1. Pick `.bin` (Android Storage Access Framework picker)
2. Pre-flight: shows file size vs. known max (128 KB). Engine-off-and-stable-power checkbox (required). One-line warning: "If power drops after upload is accepted, wireless recovery may not work; use USB rescue."
3. Hotspot setup + push: collects or confirms the phone hotspot SSID/password, requires WPA2-Personal, opens Android hotspot settings for the user to toggle the hotspot on, sends BLE `maintenance` command, BLE drops, mDNS lookup resolves device IP, POSTs the file. Shows a progress bar during HTTP upload.
4. Apply: spinner with "Device is flashing. Don't disturb." message. 30 sec timeout for BLE re-discovery.
5. Verify: reads connect banner of new device, shows version token. "Update complete" or "Verification failed — try USB rescue."
6. USB rescue (failure dead-end): bundled local screen with the exact `arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port <PORT> carduino-v4/` command and bootloader-force steps. Reachable from the wizard's failure path AND from the diagnostic screen anytime.

### 4.2 State / persistence

App-side persistence is small. Use Android **DataStore** (Preferences flavor) for:

- Known devices: a map keyed on **BLE MAC address** with `{ nickname, last_known_good_version, last_seen_at }`. Nickname defaults to the BLE-advertised name; user can rename from a long-press in the device picker.
- Currently selected device MAC.
- Saved phone hotspot credentials: `{ ssid, password }`, editable from the OTA wizard and clearable from diagnostics later.

This is the "multi-aware data model with single-device UX" middle ground: there is at most one `current` device at a time, and switching between devices means going to S1, picking another, and returning. No simultaneous connections, no per-device state in memory beyond the active one. The schema doesn't preclude future v4.y multi-active work.

Persisted hotspot SSID/password in DataStore for repeat OTA runs. No persisted sensor history. No persisted command results.

### 4.3 BLE central behavior

- Scan filter: advertised local name == `CARDUINO-v4`.
- Connect to current device's MAC.
- Discover NUS service (`6E400001-...`), TX char (notify, `...0003-...`), RX char (write, `...0002-...`).
- Subscribe to TX notify on connect.
- Read firmware version + build from the connect banner's first line (§5.2). No separate `version` command needed — banner is sent on every connect.
- Periodic dump parsing: accumulate notifications into a line buffer split on `\n`. Each line of the form `oilT  =   185.2 °F   ok` is parsed against a fixed regex, fed to the dashboard view-model.
- Auto-reconnect: if the link drops with no maintenance-mode context (i.e., not part of an OTA flow), retry with backoff: immediate, 1s, 5s, 15s, 30s, 60s, then steady-state 60s. Pause backoff when the app is backgrounded; resume on foreground.

### 4.4 Manual hotspot lifecycle

Used only during the OTA wizard's push step. LOH was rejected because Android 16 gates custom LOH configuration away from regular apps; see §11 for the framework finding and bench result. Lifecycle:

1. User taps "Firmware update" and enters the wizard.
2. User picks a `.bin`; pre-flight checks file size and requires engine-off/stable-power attestation.
3. Hotspot setup screen collects the phone hotspot credentials. It must show the mandatory warning: **"must be WPA2-Personal — WPA3 / Transition will not work."** User taps **Open Hotspot Settings**; the app deeplinks to `android.settings.TETHER_SETTINGS` so the user can toggle the phone hotspot on. The app polls `ConnectivityManager.getNetworkCapabilities` to detect when hotspot appears before allowing the push to continue.
   - First-time path: user enters SSID + password manually, or taps **Import from QR screenshot**.
   - QR import path: Android SAF picker with `image/*`; decode standard Wi-Fi QR payloads of the form `WIFI:S:<ssid>;T:<sec>;P:<pwd>;;` using `com.google.zxing:core:3.5.3`; populate the fields; user confirms before proceeding. Add `implementation("com.google.zxing:core:3.5.3")` in `app/build.gradle.kts` (pure Java, ~200 KB, no Play Services dependency).
   - Repeat path: SSID + password are pre-filled from DataStore. User can edit them or tap **Use saved**. A **Clear saved hotspot** action belongs on the diagnostics screen later; out of scope for this document's screen detail.
4. App captures the phone hotspot `Network` via `ConnectivityManager.NetworkCallback` filtered by `TRANSPORT_WIFI` + `NET_CAPABILITY_LOCAL_NETWORK`. Bench-verify the exact filter on Pixel 8; if Android does not report the phone's own AP with that capability, use the narrowest fallback heuristic that still lets OkHttp bind sockets and `NsdManager.discoverServices` scope to the hotspot Network.
5. App sends BLE `maintenance ssid=<pct> psk=<pct> pwd=<pct>\n` using the confirmed SSID/password and generated OTA password. Firmware behavior is unchanged.
6. R4 joins the user-enabled hotspot. App waits ~5s after BLE drop, discovers `carduino-v4._arduino._tcp.local` via `NsdManager.discoverServices("_arduino._tcp.", PROTOCOL_DNS_SD)` scoped to the captured hotspot Network, then pushes the firmware over HTTP as before.
7. On terminal success or failure, app prompts: "You can turn the hotspot off now." Android does not expose unprivileged APIs to toggle tethering off programmatically.

The HTTP request must be scoped to the captured phone-hotspot Network. On Android 10+, use that Network's `socketFactory` or `ConnectivityManager.bindProcessToNetwork(hotspotNetwork)` so the OkHttp socket goes through the hotspot path rather than cellular. Verify this works on the Pixel 8 as part of implementation (§11).

### 4.5 OTA HTTP push (Kotlin sketch, illustrative)

```kotlin
val client = OkHttpClient.Builder()
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

val body = sketchBinFile.asRequestBody("application/octet-stream".toMediaType())

val req = Request.Builder()
    .url("http://${deviceIp}:65280/sketch")
    .post(body)
    .header("Authorization", Credentials.basic("arduino", otaPassword))
    .build()

client.newCall(req).execute().use { resp ->
    when (resp.code) {
        200 -> /* upload accepted, device applying */
        401 -> /* wrong password (bug; we generated it) */
        413 -> /* binary too large (bug; we size-checked) */
        else -> /* other — show body to user */
    }
}
```

OTA password is generated by the app per session (random 16+ chars) and sent in both the BLE `maintenance` command and the HTTP `Authorization` header.

---

## 5. Firmware-side additions

These are the only firmware changes for v4.x. Bench-validated v4 behavior is otherwise unchanged.

### 5.1 New BLE commands

#### `maintenance ssid=<pct> psk=<pct> pwd=<pct>\n`

Args are percent-encoded UTF-8 to keep the existing single-space command parser intact. Firmware decodes percent escapes into fixed-size buffers, validates non-empty values, rejects malformed escapes.

**Buffer sizes** in firmware: SSID ≤ 32 bytes (Wi-Fi standard), PSK ≤ 63 bytes (WPA2 max), pwd ≤ 32 bytes. Encoded line length budget: bump RX command buffer from 64 to 256 bytes for v4.x (small RAM cost; current 32 KB usage is 32%, headroom is fine).

**Success reply:**
```
OK maintenance armed timeout=3000\r\n
```

**Failure replies:**
```
ERR maintenance bad-args\r\n        (validation failed)
ERR maintenance arg-too-long\r\n    (line over RX buffer)
ERR maintenance busy\r\n            (already in non-NORMAL state)
ERR maintenance wifi-unavailable\r\n (early failure of WiFi.beginAP/STA setup)
```

#### `maintenance abort\n`

Cancel armed maintenance before BLE drop only. Not supported after BLE teardown.

**Success (still armed):** `OK maintenance aborted\r\n`
**Too late:** `ERR maintenance too-late\r\n`

### 5.2 Banner version token

Existing connect banner sends reset cause / boot counter / last fatal err per `DESIGN.md:542-543`. Extend it to start with a parseable version line:

```
CARDUINO-v4 version=4.x.y build=<git>\r\n
reset=<cause> boot=<n> last_err=<code>\r\n
... (rest of existing banner)
```

The first line gives the app a reliable verification source post-OTA.

### 5.3 Maintenance state machine

| State | Entry action | Exit trigger | Timeout / recovery |
|---|---|---|---|
| `MM_NORMAL` | Normal sensors / CAN / BLE. | BLE `maintenance ...` with valid args → `MM_ARMED`. | None. Existing 1 sec watchdog (`DESIGN.md:594-598`). |
| `MM_ARMED` | Reply `OK maintenance armed timeout=3000`; LED maintenance-pending pattern; send final CAN status frame with maintenance bit set; stop periodic BLE dumps. | 3 sec elapsed → `MM_BLE_DRAIN`. `maintenance abort\n` → `MM_ABORTING`. | 10 sec absolute max → `MM_ABORTING`. |
| `MM_BLE_DRAIN` | 1 sec drain window for in-flight BLE TX. No new BLE traffic generated here — the app already received `OK maintenance armed timeout=3000` in `MM_ARMED` and knows BLE will go down. | Drain window elapsed → `MM_WIFI_JOINING`. | Hard 1 sec cap. |
| `MM_WIFI_JOINING` | End BLE; switch modem to STA; `WiFi.begin(ssid, psk)`. | `WL_CONNECTED` and `localIP()` valid → `MM_OTA_READY`. | 30 sec join deadline → reboot to NORMAL (recovery). |
| `MM_OTA_READY` | `ArduinoOTA.begin(localIp, "carduino-v4", pwd, InternalStorage)` — JAndrassy default global is the mDNS variant, so `_arduino._tcp.local` is advertised automatically; LED maintenance-ready pattern. | Library's `onStartCallback` fires → `MM_UPLOAD_APPLYING`. | 5 min idle → reboot to NORMAL. |
| `MM_UPLOAD_APPLYING` | LED maintenance-applying pattern. Call `ArduinoOTA.poll()` from main loop. | Successful upload causes library to `apply()` and `NVIC_SystemReset` (control never returns). Library `onErrorCallback` → `MM_OTA_ERROR`. | Hardware watchdog catches stuck-during-poll. Reboot recovers. |
| `MM_OTA_ERROR` | LED error pattern; ERR99 on display; clear WiFi. | 5 sec elapsed → reboot to NORMAL. | 5 sec hard. |
| `MM_ABORTING` | Reply `OK maintenance aborted` (if BLE still up); restore normal LED. | Immediate → `MM_NORMAL`. | None. |

State implementation lives in new files `maintenance_mode.{h,cpp}`. The state enum is internal; only `enter_maintenance(...)` and `maintenance_tick()` are called from the main loop.

### 5.4 CAN final-status frame

DESIGN.md §5 reserves a status bit for "wireless update state" in CAN frame 2 (currently bit 2, named `OTA in progress` in v4 with handling deferred to v4.x — see `DESIGN.md:349`). On entry to `MM_ARMED`, send one final CAN frame with this bit set, then halt CAN broadcasts. The MS3-side log will show a clean "going down" marker rather than an unexplained data freeze.

### 5.5 No new EEPROM records

LOH-generated creds are received per session and used immediately; nothing persisted. No firmware-side OTA password storage. v4 EEPROM layout (boot counter, reset cause cache, last fatal error) is unchanged.

### 5.6 Sketch size impact

Estimated additions:
- `maintenance_mode.{h,cpp}`: ~3 KB
- New BLE command parsing + percent-decode helpers: ~1 KB
- ArduinoOTA library + InternalStorageRenesas: ~6 KB (library overhead)
- Banner formatting changes: <100 bytes

Estimated total: ~10 KB. v4 baseline is 104,880 bytes / 128 KB max — gives us ~13 KB headroom. Tight but fits. **Plan to verify post-implementation; if we overflow, the easy out is to ship the LED matrix and event-log code paths conditionally.**

---

## 6. OTA wire protocol summary

Pure HTTP `POST /sketch HTTP/1.1` to port 65280. Headers: `Authorization: Basic base64("arduino:<otapw>")`, `Content-Length`, `Content-Type: application/octet-stream`. Body: raw `.bin` from `arduino-cli compile`. No `.ota` container, no LZSS, no chunked transfer. Server replies 200 / 401 / 413 / 414 / 500. Full protocol notes in `prototypes/ota_arduinoota/notes-protocol.md`.

---

## 7. Failure modes & recovery

| Failure | Detection | Recovery |
|---|---|---|
| User selected wrong hotspot security mode | Same as wrong-PSK: R4 hits `MM_WIFI_JOINING` 30 sec timeout, then BLE rediscovers without OTA completion | Wizard surfaces "Hotspot security check — must be WPA2-Personal, not WPA3 or Transition. Open hotspot settings and switch." |
| Phone hotspot Network capture timeout | `ConnectivityManager.NetworkCallback` doesn't fire within 15 sec of user confirming hotspot is on | "Hotspot didn't come up. Verify Wi-Fi/Bluetooth aren't conflicting and retry, or use USB update." |
| mDNS lookup times out (device on hotspot but not advertising) | `NsdManager.discoverServices` 15 sec timeout without resolving | "Device didn't appear on hotspot. Verify hotspot is up and try again, or use USB update." Possible causes: WiFi join failed silently, mDNS responder not bridged by modem firmware, or phone hotspot client isolation. |
| WiFi join fails on Carduino (wrong PSK, AP unavailable) | `MM_WIFI_JOINING` 30 sec timeout → reboot | Carduino comes back to `MM_NORMAL` and BLE-advertises. App sees BLE rediscovery without OTA completion → "WiFi join failed, retry." |
| HTTP push fails before 200 (4xx, network error, write timeout) | OkHttp call returns error or non-200 | Active sketch is intact (no `apply()` ran). User retries. |
| HTTP push outcome unknown (read timeout after sending body) | OkHttp read times out | Wait 30 sec, scan BLE for device. If banner shows new version → success despite missed response. If banner shows old version → upload didn't apply, retry. If no device found → USB rescue. |
| Apply succeeds but new firmware doesn't boot | App's 30 sec post-200 BLE re-discovery times out | Show USB rescue screen with copy-paste `arduino-cli` command. |
| Carduino stuck in `MM_OTA_READY` (user didn't push within 5 min) | 5 min state machine timeout | Auto-reboot to NORMAL. App sees BLE rediscovery, no version change, treats as "user-cancelled by inaction". |
| Carduino stuck in `MM_UPLOAD_APPLYING` mid-transfer (hung TCP) | 8 sec hardware watchdog | Reboot. Active sketch intact; staging area discarded on next OTA's `_storage->open()`. App sees rediscovery without version change → retry. |

---

## 8. USB rescue path

A bundled in-app screen (no internet required) showing:

1. **Plug in USB.** Connect the R4 directly to a laptop with the Arduino CLI installed.
2. **Force the bootloader if the device isn't seen:** double-tap the reset button to enter bootloader mode (verify procedure on Matthew's specific R4 clone before publishing).
3. **Re-flash with:**
   ```
   arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port <PORT> /path/to/carduino-v4/
   ```
4. **Verify** by re-opening the app and confirming the banner version.

Reachable from: the OTA wizard's failure dead-end, AND the diagnostic actions screen (so it's available without an active connection).

---

## 9. Out of scope (deferred)

| Item | Scope | Rationale |
|---|---|---|
| Two-phase OTA commit | v4.y or later | Library architecture makes naive callback-block approach unsafe; full implementation requires forking pollServer's apply step. v1 accepts USB-rescue as the failure path. |
| GitHub Releases auto-fetch | v4.y or later | Local file picker handles the workflow; auto-fetch is a quality-of-life add. |
| Sensor session recording / replay | v4.y or later | TunerStudio handles primary logging via MS3. Adding phone-side capture is bloat. |
| Engine-off auto-detection | v4.y or later | RPM signal availability is gated on CAN being active, but CAN halts during maintenance. Reliable check requires hardware that's not present. User attestation is fine. |
| iOS port | indefinitely | Single user, single phone. |
| Multi-device active-at-once | v4.y or later | Data model supports it; UI does not. Single-device concurrent use covers the foreseeable need. |
| Manual-hotspot fallback when LOH fails | v4.x — adopted as primary path | LOH custom-config gated by WorkSource priority on Android 16+; see §11 for finding. |
| LED matrix OTA progress visualization | v4.y or later | Phone is the UI during OTA; firmware loop room is limited. |
| Calibration helpers in app | indefinitely | Calibration happens at the bench with USB connected. Keep terminal-only. |

---

## 10. Implementation phasing (completed)

Phases L-P in `IMPLEMENTATION-PLAN.md` are the executable build plan; Phase J is superseded.

| Phase | Tasks | Status | What |
|---|---|---|---|
| L | 53-55 | ✅ done | Bench prototypes — R4 ArduinoOTA listener verified on phone hotspot |
| M | 56-62 | ✅ done | Firmware additions — maintenance state machine, BLE commands, banner version, CAN status frame, LED maintenance patterns |
| N | 63-69 | ✅ done | Android app foundation — Compose scaffolding, BLE central, dashboard, picker, autoreconnect, diagnostics |
| O | 70-76 | ✅ done (75 deferred to first-OTA bench data) | OTA wizard — file picker, manual hotspot, mDNS, HTTP push, wizard, USB rescue |
| P | 77-80 | ⏳ in-progress | End-to-end bench + in-car validation, doc cleanup |

Task 75 (BLE-reconnect timing tune) is gated on the first real OTA cycle producing measured timings. Task 77 (bench E2E) and Task 78 (in-car E2E) require real bench/in-car runs.

---

## 11. Open verification items (must do during implementation)

- ~~**R4 `WiFiServer` accepts incoming connections on phone hotspot with modem firmware 0.6.0.**~~ ✅ **VERIFIED 2026-05-05** via Phase L Task 53 bench — see `prototypes/ota_arduinoota/README.md`. R4 accepted HTTP POST, applied firmware, rebooted into pushed sketch.
- **R4 mDNS responder works in STA mode on phone hotspot.** JAndrassy's default `ArduinoOTAMdnsClass` global registers it; depends on `WiFiUDP::beginMulticast` reaching the modem properly. Not directly verified yet — Task 53 bench couldn't browse mDNS post-reboot since the rebooted test-sketch lacks WiFi. Verify during Task 71/72 implementation when the production app does the lookup.
- **Phone hotspot Network capture works on Pixel 8** — confirm `ConnectivityManager.NetworkCallback` fires for the phone's own hotspot AP and that OkHttp `socketFactory` routes through it.
- **`NsdManager` resolves `_arduino._tcp` services on phone-hotspot Network specifically.** Android may scope mDNS to a specific Network; verify `discoverServices` works against the captured phone-hotspot `Network`.
- **Bootloader-force procedure on Matthew's R4 clone.** Double-tap reset to enter the BOSSA bootloader is the standard Arduino-OEM procedure; clone behavior may differ. Verify before shipping the USB-rescue screen.
- **Sketch size after firmware additions.** Estimated ~10 KB, ~13 KB headroom. Verify post-implementation with `arduino-cli compile` size output. If we overflow, candidates to cut: compact debug strings in `self_tests.cpp`, drop the unused `verbose` command, defer the maintenance LED patterns to a v4.y polish pass.
- **Banner format compatibility.** Adding `version=` line is technically a change to the BLE banner output; existing clients (nRF Connect, etc.) won't care, but worth a quick visual check that `Serial Bluetooth Terminal` still renders cleanly.

### 11.1 Development-environment notes (from Phase L bench)

- **`adb root` is unavailable on stock Pixel production builds**, even with the device rooted via Magisk (`su` not accessible from the adb shell context). Production-build limitation. Doesn't block real-app dev — file push, app install, logcat, dumpsys, settings access all work without root. Hotspot toggling and signature-protected APIs are the only adb-shell gaps; deeplinks like `am start -a android.settings.TETHER_SETTINGS` cover the user-flow side.
- **Hotspot AP subnet is OEM-dependent** (Pixel 8 used `10.103.19.0/24` in bench; not the typical `192.168.43.x`). The production app MUST always discover the device IP via mDNS (or BLE-reported IP if we ever bring back that path). Never assume a subnet. Already enforced by §3.2 / §4.4 design — this just reinforces the requirement with concrete data.

### 11.2 LOH custom-config dead-end (recorded so we don't redo this)

Android 16 r4 does not allow regular apps to supply an effective custom LocalOnlyHotspot config.

- In `WifiServiceImpl.LohsSoftApTracker.startForFirstRequestLocked` (`android-16.0.0_r4`, lines 3059-3064), `mIsExclusive` is only true when `currentWsPriority >= WorkSourceHelper.PRIORITY_SYSTEM`.
- When `mIsExclusive` is false, `WifiApConfigStore.generateLocalOnlyHotspotConfig` discards the app-provided `customConfig` and generates a system LOH config instead.
- A regular app's WorkSource priority is `PRIORITY_FG_APP`, never `PRIORITY_SYSTEM`. Platform-signed apps only reach `PRIORITY_PRIVILEGED`, so reflection and hidden-API access do not help; the gate is WorkSource priority, not API surface.
- `Flags.publicBandsForLohs()` is on by default on Android 16 r4 per bench observation.
- Bench result, 2026-05-05: Pixel 8, app uid 10312, requested `CARDUINO-OTA` + `WPA2_PSK`; framework returned `AndroidShare_7223` + `WPA3_SAE_TRANSITION`. R4 hit `MM_WIFI_JOINING` 30-sec timeout and rebooted.
- R4 modem firmware 0.6.0 is the latest available as of May 2024. Renesas board package 1.5.3 is latest as of February 2026, with 128-byte TX buffer. No WPA3 SAE fix is in the pipeline.

Conclusion: the production OTA path must ask the user to start a phone hotspot manually and configure it as WPA2-Personal.

---

## Appendix A. Cross-references

- Wire protocol details: `prototypes/ota_arduinoota/notes-protocol.md` (with codex fact-check at `notes-protocol-review.md`)
- Design review: `prototypes/ota_arduinoota/notes-design-review.md`
- v4 firmware design (parent doc): `DESIGN.md`
- v4 implementation history: `IMPLEMENTATION-PLAN.md` (Phase J needs to be replaced by an implementation plan based on this doc)
