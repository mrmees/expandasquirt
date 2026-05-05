# JAndrassy/ArduinoOTA — wire protocol & R4 integration notes

**Research date:** 2026-05-05
**Library:** [`JAndrassy/ArduinoOTA`](https://github.com/JAndrassy/ArduinoOTA)
**Pinned commit (master HEAD as of research):** `51032537d7c11f52424c84ac632c6df60f8fe6f9`
**Goal:** Confirm wire protocol with enough precision that an Android Kotlin/Compose client can be written without further library spelunking, and pin down the firmware-side API + R4-specific behaviors.

---

## TL;DR — the protocol is plain HTTP

The Carduino opens a TCP listener on port **65280**. Pushing firmware is a single HTTP `POST /sketch` with the raw `.bin` as the body. No binary handshake, no MD5 challenge, no `.ota` container, no LZSS compression, no chunked transfer, no signing. Authentication is HTTP Basic.

Once the device returns `200 OK`, it erases the active sketch flash and copies the staged bytes into place from RAM-resident code, then resets. Power-fail during apply → device bricked until USB re-flash.

```
POST /sketch HTTP/1.1
Authorization: Basic <base64("arduino:<password>")>
Content-Length: <bytes in .bin file>

<raw .bin payload — no header, no compression, no padding>
```

That's the entire client-side protocol. The Android app needs `OkHttp` (or `HttpURLConnection`) and the bytes from a `.bin` file. Nothing fancier.

---

## 1. Wire protocol — full request/response spec

All citations are to files in `src/` at commit `51032537d7c11f52424c84ac632c6df60f8fe6f9` unless noted.

### 1.1 Listener

- **Port:** `OTA_PORT = 65280`. Defined in `src/ArduinoOTA.h:48`.
- **Transport:** TCP, plaintext. Server is `WiFiServer` for WiFi-based devices (R4 WiFi included). `src/ArduinoOTA.h:138-141`.
- **Per connection:** the server accepts one client at a time (single-threaded `pollServer`), processes one HTTP request, then closes. `src/ArduinoOTA.h:74-77`, `src/WiFiOTA.cpp:230-336`.

### 1.2 Request line

The library reads the first line from the client and matches exactly one of:

- `POST /sketch HTTP/1.1` — sketch upload (`src/WiFiOTA.cpp:266`)
- `POST /data HTTP/1.1` — only on `ESP8266` / `ESP32`; on R4 this returns 404 (`src/WiFiOTA.cpp:261-265`)

Anything else → server flushes the body and replies `404 Not Found` (`src/WiFiOTA.cpp:267-270`).

### 1.3 Request headers

Headers are read line-by-line until an empty line (`src/WiFiOTA.cpp:246-258`). Two headers are recognized; everything else is ignored:

- **`Content-Length: <n>`** — required. Parsed by `String::toInt()` so must be plain decimal. If missing or `<= 0` → server replies `400 Bad Request` (`src/WiFiOTA.cpp:278-281`).
- **`Authorization: Basic <base64>`** — required. Server compares the entire header value (after stripping the `Authorization: ` prefix) against `"Basic " + base64Encode("arduino:<password>")` where `<password>` is what the firmware passed to `ArduinoOTA.begin(...)`. Mismatch → `401 Unauthorized` (`src/WiFiOTA.cpp:272-276`, expected value built in `WiFiOTA.cpp:80`).

The username is **always literal `arduino`**, not configurable. The password is the only configurable secret. (Important: this is HTTP Basic over plaintext TCP. On an open phone hotspot it's recoverable by anyone on the same network. Acceptable for hobby use; not a security boundary.)

**No other headers are processed.** No `Content-Type` validation, no `Expect: 100-continue` handling, no `Transfer-Encoding: chunked` support — sending a chunked request will be misparsed. `Content-Length` is the only framing.

### 1.4 Request body

- Raw bytes of the compiled sketch `.bin` — exactly what `arduino-cli compile --output-dir <x>` writes to `<sketch>.ino.bin`.
- **Not** the `.ota` container (`OtaHeader` magic, LZSS-compressed) used by `OTAUpdate::download()` and `Arduino_ESP32_OTA`. That format is irrelevant here.
- Not compressed, not encoded, not signed. Length must equal `Content-Length`.

The server reads bytes in 64-byte gulps directly into the storage layer until `read == contentLength` (`src/WiFiOTA.cpp:296-309`).

### 1.5 Storage open / size check

Before reading the body, the server calls `_storage->open(contentLength, dataUpload)`:

- If `open()` returns 0 → flush body, reply `500 Internal Server Error` (`src/WiFiOTA.cpp:283-287`).
- If `_storage->maxSize() != 0` and `contentLength > maxSize()` → close storage, flush body, reply `413 Payload Too Large` (`src/WiFiOTA.cpp:289-294`).

For R4, `maxSize()` returns `(MAX_FLASH - SKETCH_START_ADDRESS) / 2` = half of available flash. See §3 for what that means in bytes.

### 1.6 Successful upload — apply flow

If `read == contentLength` after the byte loop (`src/WiFiOTA.cpp:313-325`):

1. Server replies `200 OK` (with `Connection: close`, `Content-type: text/plain`, `Content-length: 2`, body `OK`). `src/WiFiOTA.cpp:338-360`.
2. `delay(500)` to flush the response.
3. `beforeApplyCallback()` fires if registered.
4. `_storage->apply()` runs — for R4 this is the destructive flash erase + copy from staging area + `NVIC_SystemReset()`. See §3.
5. Code after `apply()` is `while(true);` — but `apply()` doesn't return on R4; the chip resets inside it.

**On the wire from the client's POV:** the client sees the full HTTP/1.1 response (`HTTP/1.1 200 OK\r\nConnection: close\r\nContent-type: text/plain\r\nContent-length: 2\r\n\r\nOK`), then the TCP socket is closed by the server. Then the device disappears from the network for several seconds while it reboots. The client should treat the 200 response as "transfer accepted, device is now applying" — not "device is back online and running new firmware."

**Timing detail (verified during fact-check):** `sendHttpResponse()` itself does the response write + 500 ms delay + `client.stop()` (`src/WiFiOTA.cpp:344-355`). Then `pollServer()` does another `delay(500)` before `beforeApplyCallback()` and `_storage->apply()` (`src/WiFiOTA.cpp:316-323`). So the response is intentionally fully flushed and the socket closed *before* the destructive apply begins — there is no obvious server-side race where apply starts while the 200 is still on the wire. Total budget between byte transfer end and start of flash erase: ~1 sec.

### 1.7 Partial/short upload

If the client's TCP connection drops mid-body (`!client.connected()` exits the read loop before `read == contentLength`):

- Server replies `414 Payload size wrong` (yes, repurposing 414 for size mismatch — the HTTP standard meaning is "URI Too Long", but the library's status-line literal is `Payload size wrong`).
- `_storage->clear()` is called (no-op on R4 — `InternalStorageRenesas::clear()` is empty in `InternalStorageRenesas.h:36`).
- `client.stop()` and the listener returns to idle.

The active sketch is **not** affected on a partial upload. Only `apply()` writes to the active sketch region.

### 1.8 Error responses summary

| HTTP code | Trigger | Status text on the wire |
|---|---|---|
| 200 | Successful upload, before apply | `OK` |
| 400 | `Content-Length` missing or `<= 0` | `Bad Request` |
| 401 | Authorization header mismatch | `Unauthorized` |
| 404 | Request line not `POST /sketch HTTP/1.1` | `Not Found` |
| 413 | `Content-Length` exceeds `_storage->maxSize()` | `Payload Too Large` |
| 414 | Connection dropped before full body received | `Payload size wrong` |
| 500 | `_storage->open()` returned 0 | `Internal Server Error` |

(Citations: each `sendHttpResponse(client, code, status)` call in `src/WiFiOTA.cpp:266-294`.)

---

## 2. Firmware-side API (R4 WiFi)

### 2.1 Includes & global

```cpp
#include <ArduinoOTA.h>
// On R4 (ARDUINO_ARCH_RENESAS_UNO defined), ArduinoOTA.h:32-33 includes
// InternalStorageRenesas.h, and InternalStorageRenesas.cpp:143 defines
// `InternalStorageRenesasClass InternalStorage;` — a global named
// `InternalStorage` is what you pass to begin().
//
// Also ArduinoOTA.h:141 instantiates a global ArduinoOTA template specialized
// over <WiFiServer, WiFiClient, WiFiUDP> when WiFi is in scope.
```

### 2.2 Setup

```cpp
WiFi.begin(ssid, pass);
while (WiFi.status() != WL_CONNECTED) { delay(200); }
IPAddress ip = WiFi.localIP();
ArduinoOTA.begin(ip, "carduino-v4", "<password>", InternalStorage);
```

`begin()` registers the password (only one configurable; user is hardcoded to `"arduino"`) and starts the TCP listener + mDNS responder. (`src/ArduinoOTA.h:94-101`, `src/WiFiOTA.cpp:76-82`.)

### 2.3 Loop

```cpp
ArduinoOTA.poll(); // or ArduinoOTA.handle(); they're aliases
```

Calls `server.available()` to peek for an incoming client, runs `pollServer()` if one is connected. Non-blocking when there's no client. (`src/ArduinoOTA.h:74-77, 108-115`.)

If `poll()` decides to accept a client and read a full upload, **it blocks for the full transfer duration** plus the 500 ms apply delay. The Carduino main loop will not run during that window. On a phone-hotspot push of a ~110 KB binary at typical hotspot speeds, this is several seconds — the watchdog (8-sec) is at risk and CAN broadcasting will halt. See §4.2 for what that means for the maintenance state machine.

### 2.4 mDNS

The default `ArduinoOTA` global on WiFi platforms is the `ArduinoOTAMdnsClass` variant (`src/ArduinoOTA.h:141`), which advertises `<name>._arduino._tcp.local` on multicast `224.0.0.251:5353`. The mDNS PTR/TXT/SRV/A records are hand-rolled in `src/WiFiOTA.cpp:84-228`.

**For an Android client we don't need mDNS** — the app already knows the device IP from the BLE handshake (we'll have negotiated which hotspot it's on, and Android can read the connected device's local IP via `WifiManager`). mDNS is convenient if the client is `arduino-cli` on a laptop on the same network; it's overhead for our app.

We can suppress mDNS on the firmware side by defining `NO_OTA_PORT` (which uses the non-mDNS variant on `WiFiServer`) — see `src/ArduinoOTA.h:124, 136-142`. **But** `NO_OTA_PORT` is gated on `UIPETHERNET_H` / `WIFIESPAT1`, so on R4 we'd need a different approach (e.g., instantiating `ArduinoOTAClass<WiFiServer, WiFiClient>` ourselves rather than using the default `ArduinoOTA` global). Optional optimization, not blocking.

### 2.5 Callbacks

`onStartCallback`, `beforeApplyCallback`, `onErrorCallback(int code, const char* status)` — registered via setters on `WiFiOTAClass` (members declared in `src/WiFiOTA.h`, fired from `pollServer` per their names).

We'd want `onStartCallback` to log "OTA upload starting", `beforeApplyCallback` to halt CAN, persist any state, and turn off non-essential peripherals before the destructive apply window, and `onErrorCallback` to log/display a code on the LED matrix.

---

## 3. R4-specific storage layout & apply behavior

`InternalStorageRenesas.cpp:33-49` (constructor) and `InternalStorageRenesas.cpp:135-141` (`apply()`).

### 3.1 Layout

R4 WiFi RA4M1 has **256 KB** of internal code flash (`MAX_FLASH`). The library splits it:

```
0x00000000          0x00040000 (256 KB)
+---------+---------+----------+
|         | active  | OTA      |
| boot/   | sketch  | staging  |
| reserved| (≤128KB)| (≤128KB) |
+---------+---------+----------+
            ^           ^
   SKETCH_START_ADDRESS storageStartAddress = SKETCH_START_ADDRESS + maxSketchSize
```

`maxSketchSize = (MAX_FLASH - SKETCH_START_ADDRESS) / 2`, page-aligned (`InternalStorageRenesas.cpp:34-36`). Effective max sketch size is just under 128 KB.

**Carduino v4 today: 104,880 bytes** (~102 KB). Comfortably under 128 KB. Headroom: ~25 KB before we'd overflow the staging area or active region.

### 3.2 Write path

`open(length)` erases the staging region (page-aligned) and resets the write index. If erase fails → returns 0 → server replies 500. (`InternalStorageRenesas.cpp:89-108`.)

Each `write(byte)` accumulates into a `FLASH_WRITE_SIZE` buffer (typically 64 bytes for the RA4M1's low-power code flash); when the buffer fills, it gets committed to flash via `r_flash_lp_cf_write` with IRQs disabled. (`InternalStorageRenesas.cpp:110-126`.)

`close()` pads the partial buffer with `0xFF` and closes the flash driver (`InternalStorageRenesas.cpp:128-133`).

### 3.3 Apply path — destructive, RAM-resident, non-recoverable on power loss

```cpp
void InternalStorageRenesasClass::apply() {
  R_FLASH_LP_Open(&flashCtrl, &flashCfg);
  __disable_irq();
  copyFlashAndReset(SKETCH_START_ADDRESS, storageStartAddress, pageAlignedLength, PAGE_SIZE);
}
```

`copyFlashAndReset` is annotated `PLACE_IN_RAM_SECTION` (`InternalStorageRenesas.cpp:64`) because it erases the same flash region the function would otherwise be executing from. It:

1. Erases the active sketch region (`r_flash_lp_cf_erase`, page-aligned).
2. Loops: read 16×FLASH_WRITE_SIZE (1 KB) from staging into a stack buffer, write it to the active region.
3. `NVIC_SystemReset()` when done.

**Power-fail behavior:** if power drops between step 1 and step 3, the active sketch region is partially or fully erased. The chip will not boot back into the old firmware until recovery via USB-DFU/BOSSA. There's no A/B image scheme, no rollback, no boot counter watchdog of the new firmware before commit.

**Important nuance (per fact-check review):** the bootloader and reserved flash regions are **not** erased — `copyFlashAndReset` operates on `[SKETCH_START_ADDRESS, SKETCH_START_ADDRESS + pageAlignedLength)`, not from address 0. So a power-fail during apply leaves the active sketch region in an indeterminate state, but the BOSSA-compatible bootloader survives. Recovery is "plug in USB, force the bootloader, re-flash via `arduino-cli upload`" — the same recovery procedure used when a sketch hangs. Not "brick"; "needs USB rescue."

This means:
- The car must not be cranked during apply (battery sag → modem brownout → USB rescue needed).
- The phone must not lose its hotspot during apply (post-200-OK, on the apply itself, the phone is already disconnected; the risk is the device losing rail voltage).
- The Carduino's buck-boost regulator and capacitor are the only safety net.

The app UX should warn the user before pushing: "Engine off, key off, don't disturb the car for ~10 seconds after upload. If the device doesn't come back, USB rescue procedure: [link]."

---

## 4. Risks / open questions for the Android client + firmware integration

### 4.1 Modem bridge: does the R4 ESP32-S3 modem firmware (0.6.0) bridge raw TCP server cleanly?

**Status: not directly tested for `WiFiServer` raw-TCP listening on R4 firmware 0.6.0.**

The Carduino has gone through Path α (modem FS write) and Path β (modem-side OTA download) on this exact firmware revision. Both involve modem cooperation. Path α exposed a real bug in `WIFI_FILE_APPEND`. Path β (TCP client out to a CDN) worked.

What's untested for *us*: opening a `WiFiServer` and accepting an incoming TCP connection on a port the modem doesn't normally bridge. The modem firmware's `+CIPSERVER` / equivalent AT command path needs to be functional.

**Action item:** before locking the Android client architecture, we must do a 1-hour bench prototype that:
1. Brings up the R4 in WiFi STA mode against the phone hotspot.
2. Calls `ArduinoOTA.begin(...)` with a known password.
3. From a laptop on the same hotspot, runs `arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port <ip>:<port> --upload-field password=... <test_sketch>` (or `curl -u arduino:<pwd> -X POST -H "Content-Length: $(stat -c%s test.bin)" --data-binary @test.bin http://<ip>:65280/sketch`) and verifies the test sketch boots.
4. If yes → the Android client is unblocked. If no → we have to figure out what `WiFiS3` / modem 0.6.0 don't support and either work around or block on a modem firmware update.

This is "prototype Task 53" or similar — needs to happen before the v4.x app gets implementation effort.

### 4.2 Apply blocks the main loop ⇒ CAN broadcast halts ⇒ MS3 sees flatlines

The 5-10 second apply window (transfer + erase + copy) means CAN frame production stops. MS3 PNP's CAN receive logic (per DESIGN.md §5.4) needs to tolerate a several-second gap without faulting. Given the maintenance flow is operator-initiated (engine off, ignition off), this is fine — but it's worth wiring the `beforeApplyCallback` to send one final CAN frame with `health = 0x00` and a clear "going down" status flag before apply, so any logging downstream has a clean marker.

### 4.3 No rollback

If the new firmware doesn't boot, recovery is USB-only. We should consider:

- **Two-phase commit:** the app pushes the firmware, gets 200 OK, **storage closes but apply does not run yet**, then a separate BLE/network command from the app actually fires `InternalStorage.apply()`. That way a pushed-but-not-applied state is recoverable by reboot back into the old firmware (the staging area is overwritten on next OTA but the active sketch is intact).

  **Implementation approach (revised after fact-check):** the obvious "register a `beforeApplyCallback` that blocks waiting for BLE confirm" is **not viable** — `pollServer()` has not returned to the main loop, so BLE servicing is starved and the watchdog will fire before any confirm arrives. The right approach is to **not call `ArduinoOTA.poll()` at all** and instead use a small custom server flow (or fork `pollServer` and remove the auto-apply call), letting the library's `_storage->open/write/close` do their work but firing `apply()` separately from a top-level command handler that's reached after `pollServer` returns. This is more work than a callback registration but is the only architecture that doesn't risk WDT-during-confirm.

  Even simpler v4.x v1: skip two-phase commit, accept the USB-rescue failure mode for now, document it. Add two-phase in v4.y if it becomes a real problem after we've shipped a few real OTAs.

### 4.4 Single-shot listener vs. retry

`pollServer` only handles one full request per call. If the Android client gets a 401 or 414 mid-transfer, it can immediately retry (the listener is still up). If the upload succeeds (200), the device resets and the listener is gone until the firmware comes back up — but the new firmware presumably doesn't need OTA active until next maintenance cycle anyway.

### 4.5 No server-side timeout

The library's read loop (`src/WiFiOTA.cpp:299-309`) is `while (client.connected() && read < contentLength)` with no upper bound. A stalled client TCP connection that never closes will keep the device in `pollServer` forever. The R4 watchdog at 8 sec will catch this and reboot, leaving the staging area in a partial state (no apply happens). The next attempt re-erases staging in `open()` so it's recoverable.

The Android client should set TCP socket write timeouts (suggest 30s per chunk) and ditch the connection if it stalls. OkHttp default is 10s, fine.

### 4.6 Authorization is HTTP Basic — passive sniff exposes password

Anyone on the same hotspot can see the base64 auth header in cleartext. Threat model: ~zero (the user's own phone hotspot for ~30s during a maintenance window, in their driveway). Mitigation: use a generated-per-device password baked into firmware at build time (already a `secrets.h` slot we could repurpose).

### 4.7 IP discovery on the Android side

The R4 needs to be on the phone hotspot. The phone knows its own DHCP server and can list connected clients via `WifiManager` ARP cache, but Android doesn't expose this directly to apps without elevated permissions. Options:

- **Hardcoded:** firmware tells the BLE-connected app what IP it got via DHCP (BLE command response), then the app connects to that IP. Cleanest. The R4 reports `WiFi.localIP()` over BLE before the BLE link drops. Good.
- **mDNS:** Android does support mDNS via `NsdManager`. Library already advertises `_arduino._tcp.local`. This works without any firmware-side BLE coordination but takes a few seconds.
- **Hardcoded IP via DHCP reservation on phone:** brittle.

Plan: **the BLE-reported-IP is the primary path**; mDNS as fallback if the BLE link drops before the IP is reported.

---

## 5. Reference client (Go) — verified against source

Authoritative client used by `arduino-cli`: [`arduino/arduinoOTA`](https://github.com/arduino/arduinoOTA), pinned at commit `1e276847c2c8d4e17431d9401f1aacbf59ba08a4` (master HEAD as of research). Single-file program, `main.go`, 259 lines.

**The exact request the Go client sends** (`main.go:146-162`):

| Element | Value | Source |
|---|---|---|
| Method | `POST` | `main.go:146` |
| URL | `http://<host>:<port><uploadEndpoint>` — `uploadEndpoint` is the `--upload` CLI flag, set by Arduino IDE to `/sketch` | `main.go:146`, IDE board config |
| `Content-Type` (binary mode) | `application/octet-stream` | `main.go:154-155` |
| `Authorization: Basic <base64(user:pass)>` | via Go stdlib `req.SetBasicAuth(*username, *password)`. Username is the `--username` CLI flag, set by Arduino IDE to `arduino`. | `main.go:160-162` |
| `Content-Length: <n>` | auto-set by Go stdlib from `bytes.Buffer` length | Go `net/http` default behavior |
| `Host: <host>:<port>` | auto-set by Go stdlib | Go `net/http` default behavior |
| `User-Agent: Go-http-client/1.1` | auto-set by Go stdlib | Go `net/http` default behavior |
| `Accept-Encoding: gzip` | auto-set by Go stdlib | Go `net/http` default behavior |
| Body | raw `.bin` bytes (when `--bin-mode=true`, which the IDE sets for compiled sketches) | `main.go:128-129, streamToBytes` |

**For our Android client (Kotlin/OkHttp):**

```kotlin
val client = OkHttpClient.Builder()
    .writeTimeout(60, TimeUnit.SECONDS)  // R4 + WiFiS3 can be slow; default 10s is too short
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

val body = sketchBinFile.asRequestBody("application/octet-stream".toMediaType())

val req = Request.Builder()
    .url("http://${deviceIp}:65280/sketch")
    .post(body)
    .header("Authorization", Credentials.basic("arduino", devicePassword))
    .build()

client.newCall(req).execute().use { resp ->
    when (resp.code) {
        200 -> /* upload accepted, device applying */
        401 -> /* wrong password */
        413 -> /* binary too large */
        else -> /* other error — surface code + body to user */
    }
}
```

The 60-second timeout is informed by JAndrassy issue #240 (closed): the IDE upload tool's default 10-sec timeout was too short for R4, fixed by raising to 20 sec. We give ourselves 60 to absorb hotspot variability.

---

## 6. Confidence summary

| Claim | Confidence | Source |
|---|---|---|
| Protocol is HTTP `POST /sketch HTTP/1.1` with Basic auth and Content-Length | **High** — read directly from `WiFiOTA.cpp` master HEAD | `src/WiFiOTA.cpp:266`, `:80` |
| Port is 65280 | **High** | `src/ArduinoOTA.h:48` |
| Body is raw `.bin`, no compression / container | **High** | `src/WiFiOTA.cpp:296-309`, no decode |
| `apply()` is destructive (active sketch region) but bootloader survives → USB rescue possible, not "bricked" | **High** | `InternalStorageRenesas.cpp:135-141, 64-87`, ranges from `SKETCH_START_ADDRESS` not 0 |
| R4 sketch + staging fit in 256 KB → max sketch ~128 KB; current sketch 102 KB → fits | **High** | `InternalStorageRenesas.cpp:33-36`, `arduino-cli` size output |
| Server-side username is hardcoded `arduino` | **High** | `src/WiFiOTA.cpp:80` |
| Reference Go client wire format (Content-Type, auth method, body encoding) | **High** | `arduino/arduinoOTA@1e276847c2c8d4e17431d9401f1aacbf59ba08a4/main.go:146-162` |
| The R4 modem (firmware 0.6.0) supports `WiFiServer` accept on port 65280 from a hotspot client | **Medium — supported but unverified for our topology** | bridge firmware PR `#22` (merged into 0.4.1, present in 0.6.0) implements `_SERVERACCEPT` AT command; `WiFiS3` ships server examples; no end-to-end verification on hotspot. **1-hour bench prototype required before app implementation begins.** |
| Two-phase commit via blocking `beforeApplyCallback` | **Rejected** — risks WDT during confirm-wait | `src/WiFiOTA.cpp:316-323` (called from inside pollServer, before main-loop returns) |
| Apply timing fits within safe maintenance window with engine off | **Medium-high** | Timing inferred (~5-10s); no measurement on hardware yet |

---

## 7. What this means for the Android app design

1. **OTA push is HTTP. Treat it as such.** OkHttp `POST` with a `RequestBody` from a `File` or `InputStream`. Set `Content-Length` explicitly (don't rely on chunked encoding — the firmware doesn't support it). Add `Authorization: Basic`. That's the whole client.

2. **No need for `arduino-cli` on the phone.** Termux idea from DESIGN.md §6.4.2 was a bridge solution. We don't need it.

3. **Firmware source format is the plain `.bin` from `arduino-cli compile`.** Not `.ota`. The release artifact published to GitHub Releases (or selected from local file picker) is the same `.bin` developers already work with day-to-day. Massive win for release simplicity.

4. **Add a bench-prototype task before app implementation:** verify a `curl` push to the device on a phone hotspot actually works. If the modem doesn't bridge raw TCP server traffic, the app design pivots.

5. **Two-phase commit (push + confirm) is worth designing in.** It costs a small firmware-side modification (block in `beforeApplyCallback` until BLE confirm) and gives us a safety net against a failed-boot brick.

6. **Power and timing:** UX should make the user click through "engine off, ignition on" before triggering. App pings the device over the new firmware (BLE re-discovery) within 30s of apply to confirm boot, otherwise warn user to USB-recover.
