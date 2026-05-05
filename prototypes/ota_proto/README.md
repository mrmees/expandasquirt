# OTA Proto — Findings (Task 38)

**Date:** 2026-05-04
**Goal:** Determine whether the maintenance-mode OTA design (phone → AP →
HTTP upload → flash) is implementable with the libraries shipped in the
Arduino Renesas core.

**Verdict after bench verification:** ❌ **Path α is NOT viable.** The
`WIFI_FILE_APPEND` operation in modem firmware 0.6.0 has a bug — the first
append after a write succeeds, the second fails (`writefile()` returns 0),
and the modem becomes wedged (subsequent commands all return placeholder
values like `99.99.99` for firmware version) until a USB power-cycle.

**Pivot:** **Path β** (R4 in STA mode → `ota.download(URL, path)` from a
remote HTTP/HTTPS host) verified working in `prototypes/ota_download/`.
See that directory's notes for the chosen architecture.

---

## Library inventory (Renesas core 1.5.3)

The R4 WiFi's connectivity firmware (ESP32-S3) exposes an AT-style command
protocol over the modem-host serial link. The Arduino core wraps these with
two relevant libraries:

### `OTAUpdate` (`libraries/OTAUpdate/`)

```cpp
class OTAUpdate {
public:
    OTAUpdate();
    int setCACert(const char* root_ca);
    int begin();
    int begin(const char* file_path);
    int download(const char* url);
    int download(const char* url, const char* file_path);
    int startDownload(const char* url);                    // non-blocking variants
    int startDownload(const char* url, const char* file_path);
    int downloadProgress();                                 // 0..100 during startDownload
    int verify();
    int update();
    int update(const char* file_path);                      // <<< Path α apply step
    int reset();
};
```

All methods translate to AT commands sent to the modem (e.g. `+OTAUPDATE`,
`+OTADOWNLOAD`). The R4 host MCU does no flashing itself — the modem is the
flash master, and `update(path)` makes the modem read the file from its own
filesystem and stream the bytes back to the host MCU bootloader.

### `WiFiFileSystem` (`libraries/WiFiS3/src/WiFiFileSystem.{h,cpp}`)

```cpp
class WiFiFileSystem {
public:
    WiFiFileSystem();
    void   mount(bool format_on_fault = false);
    size_t writefile(const char* name, const char* data, size_t size,
                     int operation = WIFI_FILE_WRITE);
    void   readfile(const char* name);  // prints to Serial — utility, not consumed by code
};
```

`operation` is one of `WIFI_FILE_DELETE / WIFI_FILE_WRITE / WIFI_FILE_READ /
WIFI_FILE_APPEND` from `WiFiCommands.h`.

`writefile()` sends an AT command of the form `+FS=0,<op>,<name>,<size>` and
then passes the buffer through to the modem via `modem.passthrough()`.
**This is the bridge that lets the host MCU put arbitrary bytes onto the
modem filesystem.**

---

## Path evaluations

### Path α — Stream upload to modem FS, then `update(path)`

**Verdict:** ❌ Blocked by modem firmware bug — see "Bench verification —
results" below.

**Mechanism:**
1. R4 enters maintenance mode (BLE command), suspends sensor pipeline.
2. R4 brings up `WiFi.beginAP("CARDUINO-OTA", AP_PASSWORD)`.
3. R4 starts an HTTP server on port 80 with a minimal upload form.
4. Phone connects to AP → uploads firmware via HTTP POST.
5. R4 receives bytes in N-byte chunks from the WiFiClient socket and calls
   `fs.writefile("/update.bin", chunk, n, WIFI_FILE_APPEND)` for each chunk
   (first call uses `WIFI_FILE_WRITE` to truncate any prior file, then
   subsequent calls use `WIFI_FILE_APPEND`).
6. After upload completes, R4 calls `ota.begin("/update.bin")`,
   `ota.verify()`, then `ota.update("/update.bin")`.
7. Modem reads the file, programs the host MCU bootloader, R4 reboots into
   the new firmware.

**Why this is the right path:** The R4 only has 32 KB SRAM and a typical
firmware binary is ~100–110 KB. We cannot buffer the whole image in RAM.
`WIFI_FILE_APPEND` lets us stream — even a 256-byte chunk works, so RAM
pressure stays below 1 KB.

### Path β — `download(URL)` from a phone-hosted HTTP server

**Verdict:** Workable as a fallback, but introduces friction. The phone
needs to run an HTTP file server app, the user juggles an extra step, and
WiFi mode (R4 station vs. AP) becomes mode-dependent. Skip unless Path α
fails at bench verification.

### Path γ — Direct AT-command bypass / custom modem firmware

**Verdict:** Out of scope. Requires forking the connectivity firmware,
which buys us nothing if Path α works.

---

## Prototype sketch (`ota_proto.ino`)

The sketch in this directory:
1. Calls `WiFi.firmwareVersion()` to confirm modem comms.
2. Calls `fs.mount(true)` (format-on-fault).
3. Test 1: `writefile(TEST_FILE, payload, n, WIFI_FILE_WRITE)` — fixed
   short string. Confirms WRITE.
4. Test 2: 3× `writefile(TEST_FILE, buf, 256, WIFI_FILE_APPEND)` with
   distinguishable per-chunk patterns ("AAA…", "BBB…", "CCC…"). Confirms
   APPEND streams correctly.
5. Test 3: `fs.readfile(TEST_FILE)` — prints whole file to Serial. Pass
   criterion: output begins with `CARDUINO_PROTO_TEST_WRITE` followed by
   256 × 'A', 256 × 'B', 256 × 'C'.

### Bench verification — pass criteria

- Compile clean (no missing headers).
- `fs.mount(true)` returns without serial-printed errors.
- All 4 `writefile()` return values match the requested byte counts.
- `readfile()` output matches the expected pattern exactly.
- Total file size is `25 + 768 = 793` bytes (visible by counting the read
  output).

### Bench verification — results (2026-05-04)

After power-cycling the R4 to get a clean modem state, with `WiFi.beginAP()`
called first to switch the modem to AT-protocol mode:

```
WiFi.beginAP() ... 7              ← WL_AP_LISTENING ✓
Firmware version: 0.6.0           ← matches handoff ✓
fs.mount(true) returned           ← ✓
Test 1 WRITE: expected 25, got 25 ← ✓ single WRITE works
  append chunk 0: 256             ← ✓ first APPEND works
  append chunk 1: 0               ← ✗ second APPEND returns 0
  (no further output for 22 s)    ← test 2 chunk 2 hangs in writefile()
```

After this failure, the modem is permanently wedged — `firmwareVersion()`
returns `99.99.99`, `beginAP()` returns 9 (`WL_AP_FAILED`), and even
re-flashing the production sketch fails to recover BLE. Only USB unplug-replug
restores the modem (because the RA4M1 reset doesn't cycle the ESP32-S3).

**Likely root cause:** `WiFiFileSystem::writefile()` always passes `0` as
the first parameter (probably a session/handle index) to the `+FS=` AT
command. The modem's command parser may treat back-to-back APPEND calls
with the same handle as a protocol error, locking the FS subsystem. We
did not investigate the modem firmware to confirm.

**Workaround considered but not pursued:** call `modem.write_nowait()`
directly with the *total* firmware size in a single WRITE command, then
stream chunks from the WiFi client through `Serial2.write()` (or via
multiple `modem.passthrough()` calls), accepting the OK response only
after all bytes are sent. This bypasses APPEND entirely. Rejected because
it depends on private library internals and the canonical Path β was
proven working with no hackery.

---

## Risks / open questions for Task 39

1. **Concurrent AP + filesystem writes.** Both `WiFiServer` and
   `WiFiFileSystem` route through the same modem channel. Need to confirm
   that mid-upload `writefile()` calls don't disrupt the open WiFiClient
   connection. (Likely fine — the modem firmware multiplexes.)
2. **Throughput.** Each chunk is one AT command + serial passthrough. If
   the upper limit is, say, 5 KB/s, a 110 KB firmware takes ~22 s — fine.
   If it's 500 B/s, it takes ~3.5 min — annoying but tolerable. Measure in
   Task 39.
3. **HTTP multipart parsing.** The phone's browser will POST as
   `multipart/form-data`. We need to skip the boundary line, MIME headers,
   and trailing boundary. The minimal sketch can stream raw bytes naively
   (Task 39 is the place to learn whether this is a problem).
4. **Verification before apply.** `ota.verify()` exists but the format it
   verifies is undocumented in the header. Need to confirm whether it
   checks a CRC, a header magic, or just file size. May require
   trial-and-error during Phase J Task 44.

---

## Decision log

To be filled in by Task 40 after Task 39 prototypes the AP + upload path.
