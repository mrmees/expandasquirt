# JAndrassy/ArduinoOTA bench prototype results

**Date:** 2026-05-05
**R4 modem firmware:** 0.6.0 (per `WiFi.firmwareVersion()` from prior bench work)
**Phone hotspot:** Google Pixel 8 / Android 16, SSID "Pixel"
**Hotspot security:** WPA2-Personal (initial WPA3 transition mode failed; see Notes)
**Curl push host:** MINIMEES (Windows 10) connected to phone hotspot via WiFi 6E

## Verdict — PASS

The wireless OTA path via JAndrassy/ArduinoOTA works end-to-end on R4 with modem 0.6.0. All four design assumptions in V4X-DESIGN.md §11 are confirmed:

1. ✅ R4 modem 0.6.0 accepts incoming TCP connections via `WiFiServer` on port 65280 (the bridge firmware's `_SERVERACCEPT` AT command works in the hotspot topology, as codex's PR `#22` evidence predicted).
2. ✅ HTTP `POST /sketch` with HTTP Basic auth (`arduino:testpw`) is accepted by the JAndrassy listener.
3. ✅ Body is read as raw `.bin` bytes (no `.ota` container, no compression). 51,976 bytes of test-sketch transferred cleanly.
4. ✅ `InternalStorageRenesas::apply()` works — the R4 rebooted into the pushed firmware (test-sketch's `HELLO_FROM_OTA_TARGET` confirmed on USB serial after reboot).

## Run details

### Step 1 — listener flashed
- Sketch size: 71,028 bytes (27% of flash, well under the 128 KB half-flash apply boundary).
- Compile clean against ArduinoOTA 1.1.1.

### Step 2 — R4 joined hotspot
- Initial attempt with default WPA3 transition mode (`SecurityType = 2` per `dumpsys wifi`) → R4 timed out after 30 sec (`Timed out joining hotspot. Check creds + hotspot is on.`).
- After switching to WPA2-Personal (`SecurityType = 1`) → R4 connected within ~2 sec. RSSI -35 dBm, strong.
- DHCP-assigned IP: `10.103.19.177` on the bridged-AP subnet `10.103.19.0/24` (phone gateway `10.103.19.216`).

### Step 3 — HTTP push
```
> POST /sketch HTTP/1.1
> Host: 10.103.19.177:65280
> Authorization: Basic YXJkdWlubzp0ZXN0cHc=
> User-Agent: curl/8.17.0
> Accept: */*
> Content-Type: application/octet-stream
> Content-Length: 51976
> 
} [51976 bytes data]
* upload completely sent off: 51976 bytes
< HTTP/1.1 200 OK
< Connection: close
< Content-type: text/plain
< Content-length: 2
< 
OK
```
- Total upload time: ~6 sec
- Throughput: ~8 KB/s (limited by phone hotspot path)

### Step 4 — apply + reboot
- ~10 sec after `200 OK` response, R4 rebooted into test-sketch
- Confirmed via USB serial: continuous `HELLO_FROM_OTA_TARGET` output

## What was NOT verified in this run

- **mDNS discovery** of `_arduino._tcp.local.` — the listener shut down when the R4 rebooted into test-sketch (which has no WiFi), so we couldn't browse for it post-reboot. The JAndrassy library's default global DOES register mDNS, and protocol notes confirm the responder code path is exercised in `WiFiOTA.cpp` `pollMdns()`. Verify during real Task 72 (`NsdManager` lookup) implementation.
- **Android `LocalOnlyHotspot`** — this run used the phone's regular mobile hotspot, not LOH. LOH-specific behavior (auto-generated SSID/passphrase, `NetworkCallback.onAvailable` capturing the LOH `Network`, OkHttp `socketFactory` routing) is unverified. Will be verified during Task 71 implementation, when we use the production `LohManager` in the actual app.
- **Push from the Android prototype app** — codex wrote a Compose app that should do the equivalent OkHttp POST, but we didn't have the AS toolchain installed when this bench was run, so we used `curl` from MINIMEES instead. The Android app's HTTP push code is identical in shape to the curl request that succeeded, so confidence is high; will verify when we install Android Studio for real Phase N work.

## Notes / surprises

### WPA3 transition mode broke the join
Pixel 8 defaults its mobile hotspot to **WPA2/WPA3 transition mode** (`SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION = 2`). This is supposed to be backward-compatible — beacons advertise both RSN (WPA2) and SAE (WPA3) capabilities — but the R4's connectivity firmware 0.6.0 (ESP32-S3) doesn't reliably join in that mode. **The fix is to set the hotspot to pure WPA2-Personal (`SecurityType = 1`).**

This is worth flagging in `V4X-DESIGN.md` §7 (failure modes) and the eventual app's UX:
- The production Android app uses `WifiManager.startLocalOnlyHotspot()` which sets its own security per OEM defaults. On Pixel, LOH's `softApConfiguration.securityType` is observed to be... unknown — needs measurement in Task 71's bench step.
- If LOH defaults to transition mode, we may need to either work around it (set explicit WPA2) or accept LOH-fail-on-Pixel as a real failure mode and document the manual-hotspot fallback (which we cut from v1 — this might motivate adding it back).

### MINIMEES needed to be on the hotspot for curl
MINIMEES has WiFi 6E (RZ616) "typically unused" per the project's CLAUDE.md. We added a Pixel WiFi profile via PowerShell `netsh wlan add profile` and connected the WiFi adapter to the hotspot for the duration of the push (286 Mbps, 79% signal). Wired ethernet stayed up; routing handled both interfaces fine. Disconnect from hotspot post-test to restore normal home network state.

### `adb root` not available on production builds
The Pixel 8 is on a stock production build. `adb root` returns `adbd cannot run as root in production builds`. The phone is otherwise rooted (Magisk presumably) but `su` isn't accessible from the adb shell context. This means we can't toggle the hotspot programmatically; we deeplinked to the tethering settings screen via `am start -a android.settings.TETHER_SETTINGS` and Matthew toggled it manually.

For dev workflow going forward: this is fine for the current scope. Most adb-shell things work without root (file push/pull, app install, logcat, dumpsys, settings, etc.). Hotspot toggling and signature-protected APIs are the gaps.

## Phase L status

| Task | Status |
|---|---|
| Task 53 (R4 OTA listener via mobile hotspot) | ✅ PASS |
| Task 54 (Android LOH + OkHttp routing) | ⏸ Partial — deferred to inline verification during Task 71 implementation |
| Task 55 (decision point) | Phase M unblocked |

**Phase M (firmware-side maintenance state machine + production BLE commands + ArduinoOTA integration) is unblocked.** The architectural risk is gone; remaining unknowns are app-side LOH specifics that will be verified during real Phase N/O implementation rather than as a separate bench gate.
