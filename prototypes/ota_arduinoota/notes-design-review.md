# Design Review - Android Companion App + Firmware OTA

Review date: 2026-05-05

Scope: review of the proposed CARDUINO v4.x Android companion app and firmware maintenance-mode additions, using `DESIGN.md`, `IMPLEMENTATION-PLAN.md`, `HANDOFF.md`, `prototypes/ota_arduinoota/notes-protocol.md`, and `prototypes/ota_arduinoota/notes-protocol-review.md`.

## A. Reliability gaps

### A1. `maintenance ssid=<s> psk=<p> pwd=<otapw>` can exceed the existing BLE command limit

The summary proposes a single-line BLE command carrying SSID, hotspot PSK, and OTA password. Existing BLE conventions cap input at 64 bytes including terminator and require arguments not to contain spaces. Source: `DESIGN.md:536` and `DESIGN.md:538`. That is tight enough to fail with a normal manual hotspot SSID/password, and it will definitely fail if Matthew names a hotspot with a space. On overrun, the existing convention says the firmware discards the buffer and logs a warning, which is not a good setup UX for OTA.

Recommendation: either raise the RX command buffer for v4.x, or make this command explicitly reject values that cannot fit and return a visible `ERR maintenance arg-too-long`. For v1, I would not build a complex escaping system. Use percent-encoding for only the characters that need it, and enforce a hard decoded+encoded length budget before the app sends.

### A2. `OTA_READY` over BLE is not a reliable handoff boundary

The summary says the Carduino sends `OTA_READY ip=<x.x.x.x>` "just before BLE drops", then ends BLE and starts/uses WiFi. BLE notifications are not guaranteed to be delivered before a disconnect unless the app observes the notification. Existing BLE TX is chunked by MTU and phone-side reassembly is app responsibility. Source: `DESIGN.md:528-531`. The plan also says advertising resumes after disconnect, but that is normal-mode behavior, not this handoff. Source: `DESIGN.md:544`.

Recommendation: make the app treat `OTA_READY` as the preferred path, not the only path. If BLE disconnects before `OTA_READY`, the app should wait a short window and then either show "Carduino entered WiFi maintenance but did not report IP" with manual retry, or try mDNS as fallback. The firmware should send `OTA_READY ...\r\n`, wait a small bounded drain window, then drop BLE. Do not let this wait be unbounded.

### A3. R4 `WiFiServer` OTA listener on the phone hotspot is still unproven

The protocol notes explicitly say raw `WiFiServer` listening on R4 firmware 0.6.0 has not been directly tested for this topology. Source: `prototypes/ota_arduinoota/notes-protocol.md:231-241`. The review found supportive bridge evidence but still concluded a bench prototype is required. Source: `prototypes/ota_arduinoota/notes-protocol-review.md:32-40`.

Recommendation: before building the Android app, run the 1-hour prototype from the protocol notes: R4 joins a phone hotspot, starts `ArduinoOTA`, laptop on same hotspot pushes a `.bin` via `curl` or `arduino-cli`, and the test sketch boots. Without that, the app design rests on an unverified transport assumption.

### A4. The state machine needs more timeout exits than "5 min idle"

The summary only names a 5-minute idle reboot after listener active. That is not enough. `WiFi.begin()` can block up to 10 seconds per `DESIGN.md:173-175`, and `ArduinoOTA.poll()` can block for the full upload duration, risking the 8-second maintenance watchdog. Source: `prototypes/ota_arduinoota/notes-protocol.md:149`. The design already says maintenance watchdog is 8 seconds. Source: `DESIGN.md:594-598`.

Recommendation: every maintenance state needs a timeout and an explicit recovery action. The state table in section C below is intentionally small, but it includes bounded exits for BLE drain, WiFi join, OTA listen idle, upload/apply, and reboot verify.

### A5. App-side size gating is missing

The R4 OTA staging layout effectively limits sketches to just under half flash, not the IDE's full 256 KB number. Current Carduino v4 is around 104,880 bytes, leaving about 25 KB headroom. Source: `prototypes/ota_arduinoota/notes-protocol.md:183-188`. The protocol review also flags open JAndrassy issues confirming this half-flash limit. Source: `prototypes/ota_arduinoota/notes-protocol-review.md:25`.

Recommendation: the app must reject files larger than the known safe max before upload. Do not rely on the HTTP `413 Payload Too Large` as the first user-visible check. Also show the selected file size in the wizard so a wrong artifact is obvious.

### A6. "HTTP push timeout -> device on old fw" is only true before apply

The summary says HTTP 4xx/timeout means old firmware remains and the user retries. That is true for 400/401/413/414 and short upload paths. Source: `prototypes/ota_arduinoota/notes-protocol.md:104-108`. It is not true after a `200 OK`: the device accepted the upload and is applying. Source: `prototypes/ota_arduinoota/notes-protocol.md:78-87`. If the app times out waiting for the HTTP response, the user may not know whether the device is still old, applying, or already rebooting.

Recommendation: app state must distinguish "upload failed before 200" from "HTTP outcome unknown" from "200 accepted, waiting for BLE reboot". On unknown outcome, do not immediately retry blindly; first scan BLE for the device and read the version banner.

### A7. CAN/BLE normal operation halts during maintenance and should be intentionally marked

The notes already call out that `ArduinoOTA.poll()` blocks and CAN production stops during transfer/apply. Source: `prototypes/ota_arduinoota/notes-protocol.md:149` and `prototypes/ota_arduinoota/notes-protocol.md:247`. Since this is engine-off maintenance, that is acceptable, but it should not look like an unexplained data freeze in downstream logs.

Recommendation: before dropping normal operation, send one final CAN status frame with the reserved OTA/maintenance status bit set if feasible. `DESIGN.md` already reserves CAN status bits for WiFi AP active / OTA in progress. Source: `DESIGN.md:346-349`. If this is too much for v1, at least set the LED maintenance state before stopping BLE.

### A8. USB rescue path is required, not optional copy

Power loss during apply can leave the active sketch region invalid, while the bootloader/reserved region should survive. Source: `prototypes/ota_arduinoota/notes-protocol.md:214-216` and fact-check wording in `prototypes/ota_arduinoota/notes-protocol-review.md:51-55`. The summary mentions USB recovery instructions, but this needs to be a real app screen or bundled doc reachable without the Carduino being online.

Recommendation: ship a short local "USB rescue" page in the app: plug in USB, force bootloader if needed, run `arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port <PORT> carduino-v4/`. The exact bootloader-force steps should be verified on Matthew's R4 clone before release; I am not asserting them here.

## B. Overengineering

### B1. Multi-device support is bloat for the stated use

The framing says one user, one car, one Carduino. Persisting a known-device list keyed by BLE MAC is more app state, more UI, and more testing for no real payoff. The design non-goals also exclude multi-user/multi-client support. Source: `DESIGN.md:23-30`.

Recommendation: v1 should store one selected device. Keep "change device" as a rescan action, not a managed device picker with nicknames and last-known-good versions per device.

### B2. Manual-hotspot fallback is probably too much for v1

Manual hotspot mode complicates the exact thing most likely to fail: SSID/password entry, Android WiFi permissions, and explaining which network the phone/app is currently on. LOH failure is a predictable failure, but not every predictable failure deserves a second workflow.

Recommendation: for v1, support LocalOnlyHotspot only if the phone supports it. If `startLocalOnlyHotspot()` fails, tell Matthew to use USB update. Add manual hotspot only after LOH fails on his actual phone. This fits the project framing better than designing for the Android ecosystem.

### B3. `maintenance abort` after entry is marginal

Abort is useful during a short pre-drop countdown. After BLE has ended and WiFi is joining/listening, supporting abort costs state handling and edge-case testing. The summary says "cancel before BLE drops", which is the only version that earns its keep.

Recommendation: implement abort only in `MM_ARMED` before BLE teardown. Once WiFi transition starts, the recovery path is timeout to reboot normal mode.

### B4. Diagnostic actions screen risks app creep

Reboot, self-test, clear errors, boot info, and maybe one log view are fine because they wrap existing BLE commands. But event-log browsing can become a mini terminal. Calibration and `verbose` staying terminal-only is the right boundary.

Recommendation: keep diagnostics to one compact actions screen and raw text result display. Do not add structured log storage, filters, histories, or export.

### B5. OTA progress on the app is useful; OTA progress on LED is not

The summary already puts LED OTA progress out of scope. Keep it that way. A simple maintenance/applying pattern is enough. The phone is the UI during OTA, and the firmware already has limited loop/watchdog room during upload.

## C. Carduino maintenance state machine spec

Keep this firmware state machine small. State names below are concrete enough to implement as an enum in `maintenance_mode.h/.cpp`.

| State | Entry action | Exit trigger | Timeout / recovery |
|---|---|---|---|
| `MM_NORMAL` | Normal sensors/CAN/BLE. | BLE command `maintenance ...` with valid args. | None. Normal watchdog 1 sec per `DESIGN.md:594-598`. |
| `MM_ARMED` | Reply `OK maintenance armed timeout=3000`; set LED maintenance pending. BLE remains up. | 3 sec elapsed -> `MM_BLE_DRAIN`; `maintenance abort` -> `MM_ABORTING`. | 10 sec absolute timeout -> `MM_ABORTING`. |
| `MM_BLE_DRAIN` | Send `OTA_STARTING\r\n` if desired; stop periodic dumps; send final `OTA_READY_PENDING\r\n` or equivalent. | Final notification write attempted -> `MM_WIFI_JOINING`. | 1 sec max drain; then proceed anyway. Do not wait forever for BLE delivery. |
| `MM_WIFI_JOINING` | End BLE; switch modem to STA; call `WiFi.begin(ssid, psk)`. | `WL_CONNECTED` and `WiFi.localIP()` valid -> `MM_OTA_READY`. | 30 sec join deadline -> reboot to normal. This bounds blocking WiFi behavior noted in `DESIGN.md:173-175`. |
| `MM_OTA_READY` | Start `ArduinoOTA.begin(localIp, "carduino-v4", pwd, InternalStorage)`; LED maintenance ready. If BLE is still alive in the chosen implementation, send `OTA_READY ip=<ip> port=65280\r\n`; otherwise rely on prior/app fallback. | HTTP client starts upload via `ArduinoOTA.onStartCallback` -> `MM_UPLOAD_APPLYING`; `maintenance abort` is not available here in v1. | 5 min idle -> reboot normal, matching summary. |
| `MM_UPLOAD_APPLYING` | Set status flag / LED applying. Call `ArduinoOTA.poll()` from loop. | Successful upload causes `apply()` and reset; OTA error callback -> `MM_OTA_ERROR`. | No firmware loop timeout while inside library read loop except hardware watchdog. Feed watchdog only where safe; if stalled longer than maintenance watchdog, reset normal. |
| `MM_OTA_ERROR` | Stop OTA server if possible; show ERR99/transient maintenance error; clear WiFi. | Error reported or timeout elapsed -> reboot normal. | 5 sec -> reboot normal. Old firmware should still be active for pre-apply errors. |
| `MM_ABORTING` | Reply `OK maintenance aborted\r\n`; restore normal LED. | Immediate -> `MM_NORMAL`. | None. |

Notes:

- Do not add a separate "ready to reset" state for v1. JAndrassy `ArduinoOTA` sends 200, delays, calls `beforeApplyCallback`, applies, and resets. Source: `prototypes/ota_arduinoota/notes-protocol.md:78-87`. The app should treat 200 as "accepted/applying".
- Do not attempt two-phase commit in v1. The notes reject a blocking `beforeApplyCallback` confirmation because `pollServer()` has not returned to the main loop, so BLE confirm may never be serviced. Source: `prototypes/ota_arduinoota/notes-protocol.md:255` and `prototypes/ota_arduinoota/notes-protocol-review.md:57-61`.
- During `MM_WIFI_JOINING`, CAN broadcast will likely stop or jitter. This is acceptable only because the app requires engine-off acknowledgement.

## D. BLE protocol additions

Existing protocol constraints to preserve:

- NUS-style RX write and TX notify. Source: `DESIGN.md:514-525`.
- Commands are newline framed; input accepts `\n` or `\r\n`; output uses `\r\n`. Source: `DESIGN.md:532-535`.
- Commands are lowercase and single-space separated; arguments must not contain spaces unless encoded. Source: `DESIGN.md:537-538`.
- Current max command length is 64 bytes including terminator. Source: `DESIGN.md:536`.

### D1. App -> Carduino command

Proposed v1 command:

```text
maintenance ssid=<pct> psk=<pct> pwd=<pct>\n
```

Rules:

- `ssid`, `psk`, and `pwd` values are percent-encoded UTF-8 bytes using `%20`, `%25`, etc. This keeps the existing single-space command parser intact.
- Firmware decodes percent escapes into fixed-size buffers, validates non-empty values, and rejects malformed escapes.
- Firmware rejects the full encoded line if it exceeds the configured RX buffer. If the current 64-byte buffer stays, the app must preflight and refuse to send too-long values.
- `pwd` is the OTA Basic auth password. The HTTP username remains literal `arduino`, per protocol notes. Source: `prototypes/ota_arduinoota/notes-protocol.md:51-54`.

Success response:

```text
OK maintenance armed timeout=3000\r\n
```

Failure responses:

```text
ERR maintenance bad-args\r\n
ERR maintenance arg-too-long\r\n
ERR maintenance busy\r\n
ERR maintenance wifi-unavailable\r\n
```

Keep the error vocabulary this small. The app can map all `ERR maintenance ...` responses to a retry or USB-update instruction.

### D2. Abort command

```text
maintenance abort\n
```

Success while still armed:

```text
OK maintenance aborted\r\n
```

If too late:

```text
ERR maintenance too-late\r\n
```

Do not support abort after BLE teardown in v1.

### D3. Final ready message

Preferred final message:

```text
OTA_READY ip=192.168.43.7 port=65280 path=/sketch\r\n
```

This is deliberately plain text, line framed, and short enough to fit in one or a few NUS notifications. The app must parse it after reassembling by newline, not by notification packet. Existing TX behavior may split long lines across notifications. Source: `DESIGN.md:528-531`.

If WiFi join fails before ready:

```text
ERR maintenance wifi-join\r\n
```

But do not rely on this being delivered; WiFi/BLE transition failures can drop the link.

### D4. Applying vs ready-to-reset

For v1, the app does not need a BLE distinction between "applying" and "ready to reset". Once the HTTP POST returns 200, the library waits about 1 second, applies, and resets. Source: `prototypes/ota_arduinoota/notes-protocol.md:78-87`. BLE is already gone in this design. The app state should be:

1. `uploading` while HTTP request is in progress.
2. `applying` after HTTP 200.
3. `verifying` while scanning BLE for `CARDUINO-v4` and reading the version/banner.
4. `usb_rescue_needed` after a 30 second verify timeout.

## E. Anything else

### E1. Design drift: old Phase J AP-mode plan conflicts with the new LOH/STA design

`IMPLEMENTATION-PLAN.md` Phase J was explicitly deferred to v4.x, but its old tasks still describe the Carduino starting its own AP and serving an upload page on `192.168.4.1`. Source: `IMPLEMENTATION-PLAN.md:3611-3615` and `IMPLEMENTATION-PLAN.md:3751-3837`. The current summary instead uses Android LocalOnlyHotspot, Carduino STA mode, and `ArduinoOTA` on port 65280.

Recommendation: before implementation, write a short v4.x plan that supersedes Phase J. Otherwise a future implementer may mix AP-mode browser upload code with the newer `ArduinoOTA` HTTP `/sketch` path.

### E2. The summary says "engine-off ack", but the wording should be "engine off, stable power"

For a car-installed device, key state matters. If the Carduino only has switched 12V, "key off" may remove power. The protocol notes warn not to crank during apply because power loss can force USB rescue. Source: `prototypes/ota_arduinoota/notes-protocol.md:214-223`.

Recommendation: app copy should say: engine off, ignition/accessory state that keeps Carduino powered, do not crank or cycle power until the app says complete.

### E3. Version verification needs a concrete source of truth

The summary says the app reads the version banner and verifies. The existing BLE connection model sends a banner with reset cause, boot counter, and last fatal error on connect. Source: `DESIGN.md:542-543`. I did not see a line in the cited docs that guarantees the banner includes firmware version.

Recommendation: add an explicit `version` BLE command or guarantee that the connect banner begins with a parseable version token, e.g. `CARDUINO-v4 version=4.x.y build=<git>`. Without this, the app can confirm the device rebooted but not reliably confirm the selected sketch is running.

### E4. LocalOnlyHotspot network routing on Android needs a small app prototype

I am not claiming this is broken, but it is a risk: Android may keep general networking on cellular while LOH is active, and the app may need to bind the HTTP socket to the LOH/WiFi network. This is Android API behavior, not covered by the firmware docs here.

Recommendation: prototype `startLocalOnlyHotspot()` + OkHttp to a laptop/server on the LOH before building the whole wizard. This is parallel to the R4 `WiFiServer` bench test.

### E5. Keep no-rollback as an explicit v1 acceptance

The summary says no two-phase commit and accepts USB rescue. That is reasonable for this hobby scope. The only missing piece is making the acceptance visible in the app and docs. Source for why this matters: active sketch apply is destructive but bootloader should survive, `prototypes/ota_arduinoota/notes-protocol.md:214-216`.

Recommendation: add one line to the wizard before upload: "If power drops after upload is accepted, wireless recovery may not work; use USB rescue." That is enough. Do not build rollback for v1.
