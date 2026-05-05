# Protocol Notes Review - fact-check and gap-fill

**Review date:** 2026-05-05  
**Primary draft reviewed:** `prototypes/ota_arduinoota/notes-protocol.md`  
**JAndrassy/ArduinoOTA pinned SHA reviewed from local cache:** `51032537d7c11f52424c84ac632c6df60f8fe6f9`  
**arduino/arduinoOTA client SHA located by GitHub code search:** `1e276847c2c8d4e17431d9401f1aacbf59ba08a4`

## Task A - reference client wire format

**Verdict: incomplete / do not treat as fully verified.**

I could not complete the strict source-line verification for `arduino/arduinoOTA` because the workspace network blocks `git clone`, `gh`, and direct raw-file download. The GitHub connector confirmed that `arduino/arduinoOTA` exists, that `main` resolves to commit `1e276847c2c8d4e17431d9401f1aacbf59ba08a4`, and code search finds `main.go` hits for `http.NewRequest`, `SetBasicAuth`, `req.Header.Set`, and `Content-Type`, but the connector returned only file references, not readable source text with line numbers.

Because of the "DO NOT FABRICATE" rule, I am not adding exact `arduino/arduinoOTA` request-line/header claims here. The current draft's server-side protocol claims are still strongly supported by JAndrassy source:

- The server requires exactly `POST /sketch HTTP/1.1` on R4; anything else returns 404. Source: `JAndrassy/ArduinoOTA@51032537d7c11f52424c84ac632c6df60f8fe6f9`, `src/WiFiOTA.cpp:260-270`.
- Only `Content-Length: ` and `Authorization: ` are parsed from request headers; all other headers are ignored. Source: same commit, `src/WiFiOTA.cpp:242-258`.
- The upload body is read as raw bytes into storage until `read == contentLength`; no decoder/container/parser is involved. Source: same commit, `src/WiFiOTA.cpp:296-311`.
- The Go client must still be re-opened later from `arduino/arduinoOTA@1e276847c2c8d4e17431d9401f1aacbf59ba08a4/main.go` before claiming exact client-added headers such as `User-Agent`, `Host`, or `Content-Type`.

## Task B - JAndrassy/ArduinoOTA issues and PRs affecting R4 WiFi

Findings from GitHub issue search on 2026-05-05:

- `#274` - **open** - "arduino r4 uno RENESAS flash size". This confirms the effective OTA maximum is not the Arduino CLI's 262144-byte "program storage" number. JAndrassy states the other half of flash stores the uploaded update, and later points to the `maxSketchSize = (MAX_FLASH - SKETCH_START_ADDRESS) / 2` code. This matches local source at `InternalStorageRenesas.cpp:33-36`. Risk impact: keep a hard app-side size gate near the actual `maxSize()`, not the IDE reported 256 KB.
- `#270` - **open** - "segmentation fault when attempting to upload example to Uno R4 WiFi". The log shows the IDE invoking `arduinoOTA` against `0.0.0.0`; JAndrassy comments that this is OTA upload, not USB upload, and asks why the IDE selected IP `0.0.0.0`. Risk impact: IDE discovery/mDNS/tooling can be flaky; the Android app should use a known IP rather than relying on Arduino IDE network-port discovery.
- `#240` - **closed/completed** - "R4 Wifi upload fails with 'Error flashing the sketch'". JAndrassy points to the upload tool's 10-second timeout; reporter says setting timeout to 20 seconds fixed it. Risk impact: Android should use generous write/read timeouts and progress UI; R4 + WiFiS3 can be slow enough to trip default short uploader timeouts.
- `#277` - **closed/completed** - "Fake programmer for Arduino Uno R4 WiFi OTA". Confirms a fake-programmer path can work for R4, but the thread is mostly Arduino IDE configuration/cache friction. Risk impact: not relevant to direct Android HTTP upload except as evidence that bypassing mDNS with explicit IP is useful.
- `#250` - **closed/completed** - R4 WiFi "not enough space" with a 172140-byte file. This is consistent with the half-flash staging limit, not a modem/server-accept issue. Risk impact: same as `#274`.
- I did not find a JAndrassy issue directly about `WiFiServer::accept()` failing on R4, modem firmware 0.6.0, or R4 flash `apply()` timing beyond timeout/size reports.

## Task C - modem 0.6.0 and `WiFiServer` accept risk

**Evidence is supportive but not enough to skip bench validation.**

- `ArduinoCore-renesas` contains WiFiS3 server examples including `libraries/WiFiS3/examples/WiFiWebServer/WiFiWebServer.ino`, `WiFiChatServer`, `WiFiAdvancedChatServer`, `WiFiPagerServer`, `SimpleWebServerWiFi`, and `AP_SimpleWebServer` at commit `99f8ee40613b165de124471d9a2b15bf5e2057fb` per GitHub code search. This proves the core ships server-listener examples, but it does not prove they work on bridge firmware 0.6.0 in our phone-hotspot topology.
- The bridge firmware release notes for `arduino/uno-r4-wifi-usb-bridge` show release `0.4.1` included "AT command SERVERACCEPT for library's WiFiServer::accept()" via PR `#22`; release `0.6.0` is later than `0.4.1`, so the feature should be present in 0.6.0 unless reverted. PR `#22` is merged, with merge commit `f1c1f024b2a3982ef783b3edb358f33aa2ebf18f`.
- PR `#22` diff adds `_SERVERACCEPT` to `UNOR4USBBridge/commands.h` and implements `command_table[_SERVERACCEPT]` in `UNOR4USBBridge/cmds_wifi_netif.h`. The implementation calls `serverWiFi[sock]->available()`, stores the accepted client, marks it `accepted = true`, and returns a client socket number. Source: `arduino/uno-r4-wifi-usb-bridge` PR `#22` diff, merged 2024-02-06.
- I did not find a public forum or GitHub issue that explicitly says "WiFiServer accept works on UNO R4 WiFi bridge 0.6.0 from another client on the same phone hotspot."

Conclusion: the bridge has explicit accept support and WiFiS3 ships server examples, but the evidence is still indirect. Keep the 1-hour bench prototype before locking the Android architecture.

## Task D - questionable or under-specified draft claims

### Section 1.6 - 200 OK before apply

The broad claim is correct, with one timing correction: `sendHttpResponse()` itself drains any remaining readable bytes, writes the response, waits 500 ms, and calls `client.stop()` before returning. Then `pollServer()` waits another 500 ms, calls `beforeApplyCallback()`, and runs `_storage->apply()`. Source: `JAndrassy/ArduinoOTA@51032537d7c11f52424c84ac632c6df60f8fe6f9`, `src/WiFiOTA.cpp:313-324` and `src/WiFiOTA.cpp:338-355`.

So the response is intentionally sent and the socket is intentionally stopped before apply. There is still normal TCP uncertainty if the client/network disappears during that final second, but this is not an obvious server-side race where `apply()` starts immediately after `client.print()`.

### Section 3.3 - "bricked until USB re-flash"

This is overstated. The destructive part is real: `copyFlashAndReset()` erases from `SKETCH_START_ADDRESS`, writes staged data over the active sketch area, and resets. Source: same commit, `src/InternalStorageRenesas.cpp:64-84` and `src/InternalStorageRenesas.cpp:135-140`.

However, the function erases starting at `SKETCH_START_ADDRESS`, not address 0, and issue `#274` explicitly notes the bootloader is in flash. The safer wording is: "power loss during apply can leave the active sketch region invalid; recovery may require USB/BOSSA re-flash, but the bootloader/reserved region is not intentionally erased by this library." I did not verify the exact BOSSA fallback behavior from Renesas core source in this pass.

### Section 4.3 - two-phase commit via blocking `beforeApplyCallback`

The callback does run after HTTP success and before `_storage->apply()`. Source: `src/WiFiOTA.cpp:313-324`; callback storage is a simple function pointer in `src/WiFiOTA.h:45-47` and `src/WiFiOTA.h:69-71`.

But using it to wait for a later BLE `apply-confirm` is under-specified and risky. `pollServer()` has not returned to the sketch loop; if BLE servicing depends on the main loop, the confirm may never be processed. If a watchdog is active, a long blocking callback can reset before apply. If the intent is true two-phase commit, a cleaner design is to fork/wrap the OTA server flow so upload closes storage and returns without applying, then expose a separate firmware command that calls `InternalStorage.apply()` later.

