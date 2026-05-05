# CARDUINO v4 — Design Specification

**Date:** 2026-05-04
**Project:** Sensor adapter for MS3Pro PNP (2000 NB1 Miata, MP62 supercharged)
**Author:** Matthew Mees + Claude (Anthropic) + codex (cross-reviewed)
**Status:** Architecture and core design locked. v4 ships with USB-cable firmware updates only; wireless OTA is deferred to v4.x (§6.4). Implementation scaffolding (§11) and BLE protocol details (§6.7) added per codex review.

---

## 1. Overview

CARDUINO v4 is a small in-cabin device that reads five aftermarket analog sensors, broadcasts their values to the MS3Pro PNP ECU over the option-port CAN bus at 10 Hz, and supports wireless firmware updates via Bluetooth-triggered WiFi access point mode.

This is a clean rewrite replacing v3, which used an Arduino Uno R3 and a bare MCP2515 module. v4 targets a Freenove FNK0096 (Uno R4 WiFi clone) with a Keyestudio EF02037 MCP2515 CAN-Bus Shield.

### 1.1 Goals

1. Read five additional sensors that the MS3Pro PNP cannot read directly (it has only the 26-pin option port for expansion)
2. Make those readings available to the MS3Pro for display, logging, and use in the engine management strategy
3. Provide an in-field debug console reachable from a phone anywhere within ~10m of the car
4. Support wireless firmware updates without removing the device from the car

### 1.2 Non-goals (v4 scope boundaries)

- ❌ Receive-side CAN parsing for general use (one minimal exception: see §6.1)
- ❌ Derived calculations on-device (e.g., on-the-fly intercooler effectiveness)
- ❌ Digital outputs (relays, alarm LEDs)
- ❌ Web dashboard, MQTT publishing, Home Assistant integration
- ❌ Persistent fault journals beyond minimal reset-cause / boot-counter
- ❌ Multi-user / multi-client support

These are explicitly v5+ scope.

### 1.3 Sensor list

| # | Sensor | Type | Range | Pin | Pull-up |
|---|--------|------|-------|-----|---------|
| 1 | Oil temperature | NTC thermistor (10kΩ, B=3950) | 80–300 °F useful | A0 | 10 kΩ to 5V |
| 2 | Post-supercharger air temperature | GM open-element NTC | 60–300 °F useful | A1 | 2.49 kΩ to 5V |
| 3 | Oil pressure | 0-5V ratiometric, 0-100 PSI | 0–100 PSI | A2 | none |
| 4 | Fuel pressure | 0-5V ratiometric, 0-100 PSI | 0–100 PSI | A3 | none |
| 5 | Pre-supercharger pressure (filter restriction) | Bosch 0 261 230 146 (3-pin MAP) | 10–115 kPa absolute | A4 | none |
| — | A5 unused, reserved for future expansion | | | A5 | — |

---

## 2. Hardware

### 2.1 Microcontroller

**Freenove FNK0096 — Uno R4 WiFi clone**
- Renesas RA4M1, single-core ARM Cortex-M4, 48 MHz
- 32 KB SRAM, 256 KB flash
- 14-bit ADC (0–16383)
- 5V I/O — same domain as Uno R3, no level shifting needed for shield or sensors
- ESP32-S3 co-processor for WiFi and BLE, communicating with main MCU over internal UART
- Onboard 12×8 LED matrix (used for status display — see §6.5)
- Standard Uno form factor and pinout

### 2.2 CAN interface

**Keyestudio EF02037 MCP2515 CAN-Bus Shield**
- MCP2515 controller + TJA1050 transceiver
- Plugs directly into Uno R4 headers (5V SPI, no level shifting)
- 16 MHz crystal (confirmed on actual EF02037 silkscreen — `Y16.000H049`. Earlier design assumed 8 MHz; bench verify caught the mismatch.)
- Occupies D2 (INT) and D10–D13 (SPI)

### 2.3 Pin map

```
Pin    Function                              Notes
─────  ─────────────────────────────────     ──────────────────────────────
A0     Oil temperature (NTC thermistor)      10 kΩ NTC + 10 kΩ pull-up to 5V
                                             SAME WIRING AS v3
A1     Post-SC temperature (GM open-el NTC)  2.49 kΩ pull-up to 5V
A2     Oil pressure (0-5V ratiometric)       Direct connection — 5V-tolerant
A3     Fuel pressure (0-5V ratiometric)      Direct connection
A4     Pre-SC pressure (Bosch 0 261 230 146) Direct connection
A5     Reserved                              Unused in v4

D2     MCP2515 INT                           Shield-occupied
D10    MCP2515 CS                            Shield-occupied
D11    SPI MOSI                              Shield-occupied
D12    SPI MISO                              Shield-occupied
D13    SPI SCK                               Shield-occupied; onboard LED is unusable while CAN is active (status display via LED matrix instead — see §6.5)

D3-D9  Unused                                Reserved for future digital outputs
D0/D1  Serial USB                            Reserved (USB serial for development)
```

All sensor signals land on the R4's analog pins A0–A5. Unlike ESP32, the R4 has a single unified ADC unit — no ADC1/ADC2 split, no WiFi-vs-ADC pin conflicts.

### 2.4 Signal conditioning

Each ADC pin gets a **100 nF ceramic cap to GND** for noise immunity. Aluminum electrolytics are not acceptable substitutes — they're ineffective at the high-frequency noise we're filtering (ignition coil EMI, alternator commutation, SPI bus crosstalk).

**Pressure sensors (A2, A3, A4):** direct connection — no voltage divider needed.

**Thermistors (A0, A1):** voltage-divider topology with pull-up to 5V; thermistor between signal pin and GND.

### 2.5 Power

```
Switched 12V (key-on)
    ↓
Buck-boost regulator (Amazon B07WY4P7W8)
    ├ Input range: 8–40V
    ├ Output: clean regulated 12V at up to 3A (36W)
    ├ Efficiency: ≥95%
    ├ Ripple: 50 mV
    ├ Soft-start delay: ≤2 sec on power-up
    └ No-load: ≤10 mA
    ↓
1A polyfuse (insurance against Carduino-internal short)
    ↓
Carduino VIN (12V → onboard ISL854102 buck → 5V rails)
    ├ ISL854102 efficiency ~85-90% in this range
    ├ Heat in ISL854102 at worst-case 460 mA load: ~0.35 W (negligible)
    └ 5V rail powers MCP2515 shield + all sensor V+ supplies
```

**Heat budget:** ~1 W total across both regulators. Both run barely warm.

**Mounting recommendation:** cabin / under-dash. The buck-boost is rated -40 to +80°C operating; engine bay heat-soak (90-100°C) would exceed that. Sensors run wires to the engine bay, electronics stay in the cabin.

**Why a single 5V rail powers everything:** all sensors are ratiometric to the 5V supply. Since the R4's ADC reference is the same 5V supply (`AR_DEFAULT`), supply variation cancels out of the conversion math (numerator and denominator move proportionally). No precision voltage reference IC needed.

---

## 3. Software Architecture

### 3.1 Pattern

**Single-threaded super-loop with `millis()`-based scheduling.** No FreeRTOS, no threads, no double buffer. The RA4M1 is single-core; FreeRTOS would solve problems the hardware doesn't have.

```
loop() iterates as fast as the MCU can — typically <1 ms per pass

  if (now - lastSensorMs >= 10) {
    SensorPhase()             // 100 Hz: read 5 ADCs, EWMA, write SensorState
    lastSensorMs = now
  }

  if (now - lastCanMs >= 100) {
    CanSendPhase()            // 10 Hz: pack frames, mcp2515.sendMessage()
    lastCanMs = now
  }

  CanReceivePhase()           // every loop: drain RX buffer (filtered to ID 1512)

  BleServicePhase()           // every loop: handle peripheral state, RX commands

  if (now - lastBleDumpMs >= 200) {
    BleDumpPhase()            // 5 Hz: send periodic data to connected client
    lastBleDumpMs = now
  }

  if (now - lastDisplayMs >= 100) {
    DisplayUpdate()           // 10 Hz: update LED matrix
    lastDisplayMs = now
  }

  // Wireless OTA maintenance service is deferred to v4.x (§6.4).
```

### 3.2 Why super-loop, not RTOS

- 5 sensors at 100 Hz × ~100 µs/read = 500 µs every 10 ms = 5% CPU duty cycle for ADC alone
- 10 Hz CAN send + 5 Hz BLE dump + display updates: trivial CPU
- 48 MHz Cortex-M4 has plenty of headroom — RTOS adds failure modes (priority inversion, mutex bugs) without solving any problem we have
- Single thread = no race conditions, no shared-state mutex / double-buffer machinery

### 3.3 Critical rule: WiFi must NEVER be called inline during normal operation

The R4's `WiFiS3` library blocks up to 10 seconds during `WiFi.begin()`. v4 does not call WiFi during normal operation; any future maintenance-mode WiFi work is deferred to v4.x (§6.4).

### 3.4 Watchdog

RA4M1 Independent Watchdog (IWDT):
- **Normal mode:** 1 sec timeout
- **Maintenance mode:** deferred with wireless OTA to v4.x (§6.4)
- On timeout: hardware reset → boot back into normal mode after self-tests

---

## 4. Sensor Pipeline

### 4.1 ADC configuration

```c
analogReadResolution(14);   // 0-16383 (~0.3 mV/count at 5V reference)
analogReference(AR_DEFAULT); // 5V VCC reference (default on R4)
```

### 4.2 Per-sensor conversion

**Thermistors (A0 oil temp, A1 post-SC temp):**

Standard Steinhart-Hart B equation (single-B form):

```c
float thermistor_to_F(int adc_raw, float pullup_ohms, float r25, float beta) {
    float ratio = (float)adc_raw / 16384.0;          // 0..1, ratiometric
    float r_therm = pullup_ohms * ratio / (1.0 - ratio);
    float invT = log(r_therm / r25) / beta + 1.0 / 298.15;
    float celsius = 1.0 / invT - 273.15;
    return celsius * 1.8 + 32.0;
}
```

Per-sensor parameters:
| Sensor | Pull-up | R₂₅ | β |
|--------|---------|-----|---|
| Oil temp (A0) | 10 kΩ | 10 kΩ | 3950 |
| Post-SC temp (A1) | 2.49 kΩ | ~3520 Ω | 3984 (typical GM — verify against actual part) |

**Ratiometric pressure (A2 oil, A3 fuel):**

Assumed default: 0V = 0 PSI, 5V = 100 PSI linear.

```c
float pressure_psi(int adc_raw, float psi_at_full_scale) {
    return (adc_raw / 16384.0) * psi_at_full_scale;
}
```

⚠️ **Bench-verify before flashing:** confirm whether the actual gauge sensors are 0–5V or 0.5–4.5V output. If 0.5–4.5V, conversion needs an offset/scale step.

**Bosch 0 261 230 146 pre-SC (A4):**

3-pin MAP sensor (not TMAP — confirmed via cross-reference to GM 12591290 / 55573248).

Inferred transfer function (placeholder, treat as bench-verify):
```
kPa_abs ≈ 24.7 × V + 0.12
```

```c
float bosch_1bar_kpa(int adc_raw) {
    float v = (adc_raw / 16384.0) * 5.0;
    return 24.7 * v + 0.12;
}
```

Constants live at the top of the file as `BOSCH_SLOPE` / `BOSCH_OFFSET` for easy tuning once datasheet is verified.

### 4.3 EWMA filter (per channel, before conversion)

```c
ewma[i] = (1.0 - alpha[i]) * ewma[i] + alpha[i] * raw[i];
```

| Sensor | α | Time constant | Rationale |
|--------|---|---------------|-----------|
| Oil temp | 0.05 | ~200 ms | Slow change, filter heavily |
| Post-SC temp | 0.10 | ~100 ms | Faster under load |
| Oil pressure | 0.10 | ~95 ms | Spikes matter, but cheap senders are noisy — moderate filtering |
| Fuel pressure | 0.10 | ~95 ms | Same reasoning |
| Pre-SC pressure | 0.10 | ~100 ms | Slow-trending |

These are starting points; tune via BLE console + log review.

### 4.4 SensorState struct

```c
typedef struct {
    uint16_t  oil_temp_F_x10;          // °F × 10
    uint16_t  post_sc_temp_F_x10;
    uint16_t  oil_pressure_psi_x10;    // PSI × 10
    uint16_t  fuel_pressure_psi_x10;
    uint16_t  pre_sc_pressure_kpa_x10; // kPa × 10
    uint8_t   age_ticks[5];            // cycles each value has been held
    uint8_t   health_bitmask;          // bit per sensor, 1=healthy
    uint8_t   ready_flag;              // cleared during first ~1 sec post-boot
    uint8_t   sequence_counter;        // increments every CAN send
} SensorState;
```

---

## 5. CAN Protocol & MS3 Integration

### 5.1 Bus parameters

- **Bitrate:** 500 kbps (matches MS3Pro standard)
- **Crystal on shield:** 16 MHz (confirmed on actual hardware)
- **Mode:** Normal mode with hardware-level filtering (only ID 1512 admitted to RX buffer)

### 5.2 Bus utilization

Two TX frames at 10 Hz + minimal RX = ~0.5% bus utilization. We share the bus with the wideband (ID 1024) without conflict.

### 5.3 Frame layout (transmit)

**Frame 1 — CAN ID 1025 (0x401) — Sensor block A**
```
Byte    Field                                MS3 mapping
────    ─────────────────────────────────    ───────────────────────
0-1     Oil temperature (°F × 10) BE         CAN ADC01 @ offset 0
2-3     Oil pressure (PSI × 10) BE           CAN ADC02 @ offset 2
4-5     Fuel pressure (PSI × 10) BE          CAN ADC03 @ offset 4
6-7     Pre-SC pressure (kPa × 10) BE        CAN ADC04 @ offset 6
```

**Frame 2 — CAN ID 1026 (0x402) — Sensor block B + metadata**
```
Byte    Field                                MS3 mapping
────    ─────────────────────────────────    ───────────────────────
0-1     Post-SC temperature (°F × 10) BE     CAN ADC05 @ offset 0
2-3     Reserved (0xFFFF)                    CAN ADC06 (future)
4       Sequence counter (wraps 0-255)       —
5       Health bitmask                       —
6       Status flags                         —
7       Max held-value age (100 ms units)    — saturates at 255 = 25.5 s
```

**Health bitmask (byte 5):**
- bit 0: Oil temperature
- bit 1: Post-SC temperature
- bit 2: Oil pressure
- bit 3: Fuel pressure
- bit 4: Pre-SC pressure
- bit 5: A5 (reserved)
- bit 6-7: Reserved

**Status flags (byte 6):**
- bit 0: Ready (EWMA settled)
- bit 1: Reserved for v4.x wireless update state (was WiFi AP active)
- bit 2: Reserved for v4.x wireless update state (was OTA in progress)
- bit 3: BLE client connected
- bit 4: CAN bus errors detected (TXERR > warning threshold or recent recoveries)
- bit 5: Sensor flatline detected (any sensor)
- bit 6: Loop timing warn (recent iteration > 50 ms)
- bit 7: CAN bus-off recovery active

### 5.4 Frame layout (receive)

**Single ID listened to: 1512 (0x5E8) — MS3 dash broadcast group**
- Bytes 2-3: RPM (uint16 BE)
- Used as the engine-running gate (RPM > 500) for flatline detection eligibility

All other CAN traffic on the bus is filtered out at the MCP2515 hardware level. No application-level processing is needed for unwanted IDs — they never raise an interrupt or appear in our RX buffer.

### 5.5 TunerStudio configuration steps

1. **CAN Bus / Testmodes → CAN Receiving:**
   - CAN ADC01 — ID 1025, offset 0, size B2U
   - CAN ADC02 — ID 1025, offset 2, size B2U
   - CAN ADC03 — ID 1025, offset 4, size B2U
   - CAN ADC04 — ID 1025, offset 6, size B2U
   - CAN ADC05 — ID 1026, offset 0, size B2U
2. **Advanced Engine → Generic Sensor Inputs:** map each CAN ADC to a sensor channel; configure display unit and divide-by-10 for one-decimal display.
3. **Datalog template:** add the new channels.

---

## 6. BLE Console (Wireless OTA deferred to v4.x)

### 6.1 Default state: BLE Serial console, WiFi off

The Carduino advertises a BLE peripheral with a **Nordic UART Service (NUS)**-style profile — two characteristics, TX (notify) for output, RX (write) for input. Standard phone apps (`Serial Bluetooth Terminal`, `nRF Connect`) speak this directly.

- Always advertising during v4 normal operation; maintenance mode is deferred to v4.x (§6.4)
- Single client at a time
- Periodic data dump at 5 Hz when client subscribed

### 6.2 Periodic dump format

```
[seq=142 ready=1 health=0x1F]
  oilT  =  185.2 °F   ok
  oilP  =   58.4 PSI  ok
  fuelP =   46.1 PSI  ok
  preP  =   97.8 kPa  ok
  postT =  142.6 °F   ok
```

### 6.3 Interactive commands

| Command | Action |
|---------|--------|
| `status` | One-shot status dump |
| `verbose on/off` | Switch periodic dump from summary to per-loop debug |
| `cal pres1 raw` / `cal therm1 raw` | Show raw ADC + voltage (calibration aid) |
| `boot` | Show last reset cause, boot counter, last fatal error |
| `log` | Dump RAM event ring buffer |
| `log clear` | Wipe event log |
| `reset can` / `reset ble` | Reinit MCP2515 / BLE peripheral |
| `clear errors` | Reset fault counters and event log |
| `selftest` | Re-run boot self-tests (degraded reporting) |
| `reboot` | Soft reset |
| `maintenance` / `abort` | Reserved for v4.x maintenance mode (§6.4) |
| `help` | Print command list |

### 6.4 Firmware update strategy

**v4 ships with USB-cable updates only.** Wireless OTA is deferred to a future
release that will ship together with a companion Android app (see §6.4.3).

#### 6.4.1 Today's workflow (USB)

1. Open the enclosure (or just keep it open during dev iteration).
2. `arduino-cli upload --fqbn arduino:renesas_uno:unor4wifi --port COM<N> carduino-v4/`
3. Sketch resets and reboots into the new firmware.

This is the same workflow used throughout v4 development. No external services,
no cert management, no app dependency. Trade-off: physical access to the USB
port is required, which conflicts with the planned weatherproof in-car install
once the device leaves the bench.

#### 6.4.2 Why wireless OTA is deferred

We prototyped two wireless OTA paths during 2026-05-04 (research artifacts in
`prototypes/ota_proto/` and `prototypes/ota_download/`):

- **Path α — R4 in AP mode + HTTP upload from phone, host writes bytes
  directly to modem filesystem with `WIFI_FILE_APPEND`.** Blocked by a modem
  firmware 0.6.0 bug: the second `WIFI_FILE_APPEND` call wedges the modem
  entirely until USB power-cycle. Verified on bench.

- **Path β — R4 in STA mode pulls firmware from a hosted URL via
  `OTAUpdate::download(URL, path)`.** Verified working end-to-end against
  Arduino's CDN at `downloads.arduino.cc` (R4 booted into the test animation
  firmware after the round-trip). However:
  - Host-side `EXTENDED_MODEM_TIMEOUT = 60 s` is hardcoded in `WiFiS3` and
    too short for slow phone-hotspot uploads with RSA-4096 (Let's Encrypt
    ISRG Root X1) cert validation.
  - Producing the `.ota` container requires running `lzss.py` then
    `bin2ota.py` from `arduino-libraries/ArduinoIoTCloud/extras/tools` —
    not part of `arduino-cli compile`. Adds friction to every release.
  - Each release would need to be hosted on a public URL (GitHub Releases
    or similar) and CA chains must match host (Sectigo for `github.com`
    leafs, Let's Encrypt for `raw.githubusercontent.com`, etc.).

The cleanest wireless path identified is the third-party
[`JAndrassy/ArduinoOTA`](https://github.com/JAndrassy/ArduinoOTA) library
which supports R4 WiFi with WiFiS3. It uses a "PC pushes to device" model
over a TCP listener on port 65280. No `.ota` container, no CA management, no
host-side timeout — but it requires a companion tool on the user's phone or
laptop (e.g. `arduino-cli` running under Termux on Android) to push the bin.
That companion tool is the missing piece.

#### 6.4.3 v4.x roadmap: companion Android app

Future scope, not part of v4:

- Android companion app that handles BOTH the live BLE sensor console (the
  primary day-to-day UX, replacing terminal apps like `nRF Connect`) AND
  acts as the firmware push tool over WiFi when the user enters maintenance
  mode.
- Firmware-side: in-firmware maintenance state machine that ends BLE,
  switches modem to WiFi STA, connects to the user's phone hotspot, brings
  up the JAndrassy listener, and waits for a push.
- Apply step is handled inside JAndrassy's `InternalStorageRenesas`
  abstraction — no external bootloader interaction required.
- Eliminates the need for any externally-hosted firmware artifact. Updates
  flow phone → R4 over WiFi, with the BLE side providing the trigger and
  status display.

Until that app exists, USB updates are sufficient for the development phase
and any rare in-car update needed in v4.

### 6.5 LED matrix (12×8) status display

**Boot (~3 sec):** scrolling "INIT" + dot pattern showing init progress.

**Normal mode (subtle, low-distraction):**
- Top-left: BLE client connected indicator
- Top-right: 1 Hz heartbeat
- Bottom row: 5 sensor health LEDs (lit = healthy)
- Total LEDs lit: ~5-7 out of 96 — visually unobtrusive

**Maintenance entering:** deferred to v4.x with wireless OTA (§6.4).

**Wireless update ready:** deferred to v4.x with wireless OTA (§6.4).

**Upload in progress:** deferred to v4.x with wireless OTA (§6.4).

**Applying:** deferred to v4.x with wireless OTA (§6.4).

**Error states:** "ERR" + 2-digit code, persistent until reboot.

### 6.6 Why this design vs. alternatives considered

| Considered | Rejected because |
|------------|------------------|
| Always-on WiFi STA mode + telnet console | Console only reachable at home; complex non-blocking state machine for `WiFi.begin()`'s 10-sec block |
| Pure BLE (including OTA) | BLE OTA on R4 is "you'll regret this" — no maintained library, app runs on RA4M1 not ESP32-S3, requires custom DFU plumbing |
| Hybrid: BLE always + WiFi STA always | Dual stack risk on ESP32-S3 bridge layer (codex flagged) |
| OTA pull from local server | Requires hosting firmware somewhere; doesn't work outside home network |

Wireless OTA is deferred to v4.x; the current roadmap is in §6.4.

### 6.7 BLE protocol specifics

**Service UUID:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service standard)

**Characteristics (NUS standard UUIDs for app compatibility):**
- **TX (notify):** `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` — Carduino → phone
- **RX (write):** `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` — phone → Carduino

Using the standard NUS UUIDs (rather than custom) means `Serial Bluetooth Terminal`, `nRF Connect`, and most generic BLE serial apps recognize the service automatically without configuration.

**Device name (advertised):** `CARDUINO-v4` (visible in BLE scanners)

**MTU and fragmentation:**
- BLE 4.x default MTU is 23 bytes (20 byte payload after ATT overhead)
- BLE 4.2+ supports MTU up to 247 bytes
- ArduinoBLE on R4 negotiates MTU at connection time
- **Carduino TX behavior:** lines longer than 19 bytes are split across multiple `notify` calls. Each call sends one MTU-bounded chunk; phone-side reassembly is the app's responsibility (most BLE serial apps handle this transparently).
- **Carduino RX behavior:** commands are framed by `\n` (newline). Multiple commands in a single write packet are processed sequentially; commands fragmented across packets are accumulated in a 64-byte input buffer until `\n` arrives. Buffer overrun (>64 bytes without `\n`) discards the buffer and logs a warning.

**Line conventions:**
- Output uses `\r\n` line endings (CR+LF) for terminal-app compatibility
- Input accepts either `\n` or `\r\n` as command terminator
- Maximum command length: 64 bytes including terminator
- Commands are case-sensitive lowercase
- Whitespace between command and arguments is single space; arguments themselves must not contain spaces

**Connection state model:**
- Single client at a time (peripheral connection limit = 1)
- On connect: Carduino sends a banner with reset cause + boot counter + last fatal err
- On disconnect: advertising resumes within ~500 ms
- No pairing/bonding — open connection (acceptable for hobby use; phone app's job to filter trusted device names)

**Periodic dump throttling:**
- 5 Hz dump cadence is the maximum
- If MTU is small (BLE 4.x without negotiation), the dump may not complete within 200 ms; in that case the next dump is skipped to avoid backing up
- A connected client cannot starve the main loop because dump generation is gated by the 200 ms scheduler

---

## 7. Error Handling & Health

### 7.1 Layer 1 — Per-sensor health

Four fault dimensions per channel:

| Dimension | Detection | Debounce |
|-----------|-----------|----------|
| **Electrical** | Raw ADC < 1% or > 99% of full scale | 30-50 ms assert / 100-250 ms clear, with threshold hysteresis |
| **Implausible value** | Engineering value outside physical bounds | 100-250 ms assert |
| **Rate-of-change suspect** | Reading jump > X% per sample | Immediate set, slow decay, never hard fault — soft "suspect" only |
| **Flatline** | No >1% change for 5 sec while engine running | Auto-clear when readings change |

**Engine-running gate:**
- Primary: RPM > 500 (from CAN ID 1512 receive)
- Fallback if no MS3 broadcasts received >2 sec: oil pressure > 5 PSI AND oil pressure sensor healthy
- If both unavailable: assume engine running (better to miss flatlines than false-detect)

**Held-value age:** each held last-good value carries an `age_ticks` counter (per-sensor, internal). The CAN frame transmits the **maximum** age across all sensors in byte 7 of Frame 2 (one 100 ms unit per cycle, saturates at 25.5 s). Per-sensor ages are available via BLE for finer-grained diagnostics.

### 7.2 Layer 2 — System health

| Component | Trigger | Action |
|-----------|---------|--------|
| MCP2515 TXERR > 96 | Warning threshold (per Microchip spec) | Log + status flag bit; **do not reset** |
| MCP2515 bus-off (TXBO set) | Hard fault | Auto-recovers after 128 idle periods; count recoveries; status flag while in bus-off |
| TX queue stuck >1 sec, no progress | Real failure | Reset MCP2515 once; escalate if recurs within 5 sec |
| BLE peripheral state errors | Stack hiccup | Restart BLE on next loop |
| Loop iteration > 50 ms | Smell | Log warning, set status flag |
| Loop iteration > 1 sec (normal) / 8 sec (maint) | Hang | Watchdog fires |

### 7.3 Layer 3 — Boot self-tests

| Test | Failure → |
|------|-----------|
| ADC self-test (RA4M1 internal Vref ~1.43V ±10%) | **Halt** — bad ADC = all data garbage |
| MCP2515 SPI register read | **Halt** — hardware comms broken |
| MCP2515 loopback frame | **Degraded mode** — keep BLE up, display ERR02, retry CAN init every 10 sec |
| BLE init | Degraded — no console, sensors + CAN run |
| LED matrix init | Log only |

### 7.4 Layer 4 — Watchdog

- Normal mode: 1 sec timeout
- Maintenance mode: 8 sec timeout
- Reset → boots back into normal mode

### 7.5 Persistent state (onboard data flash)

Three fields, written only on transitions:

```c
struct PersistentState {
    uint8_t   last_reset_cause;   // POWER_ON | WATCHDOG | BROWNOUT | SOFT_RESET
    uint16_t  boot_counter;       // wraps at 65535
    uint8_t   last_fatal_err;     // 0 = none, 1-99 = ERR## codes
};
```

Read on boot, displayed via BLE on connect. ~2 flash writes per boot — negligible wear.

### 7.6 Reporting channels

1. **CAN frame** — health bitmask + status flags (machine-readable, fastest)
2. **LED matrix** — glanceable, no client needed (per-sensor health, ERR##; maintenance progress deferred to v4.x per §6.4)
3. **BLE telnet console** — detailed event log (RAM ring buffer, ~64 events)

### 7.7 Boot error codes

| Code | Meaning | Recovery |
|------|---------|----------|
| ERR01 | ADC self-test failed | Halt; manual reboot needed |
| ERR02 | MCP2515 SPI or loopback failed | Degraded mode (BLE up, retry CAN init) |
| ERR03 | BLE init failed | Degraded mode (no console, sensors + CAN run) |
| ERR99 | Reserved for v4.x wireless update failure (§6.4) | Persistent display until acknowledged |

---

## 8. Testing & Validation

### 8.1 Phase 1: Bench (before installation)

- **1.1 Smoke test** — power-up, no smoke, expected idle current draw
- **1.2 Boot self-test verification** — each ERR## displays correctly when its trigger is forced
- **1.3 Sensor pipeline (pot injection)** — sweep each ADC channel via potentiometer, verify ADC tracking + EWMA + conversion + fault detection
- **1.4 Thermistor curve verification** — ice bath / room temp / boiling water 3-point check, ±2°F tolerance
- **1.5 CAN frame inspection** — USB-CAN dongle captures frames, every byte matches spec
- **1.6 Watchdog** — inject artificial delay, verify reset + reset cause logged
- **1.7 Maintenance mode + OTA** — DEFERRED to v4.x (§6.4); not run for v4
- **1.8 Persistent state** — verify reset cause / boot counter survive resets

### 8.2 Phase 2: In-car (post-install)

- **2.1 Pre-key-on** — continuity, no shorts, secure routing
- **2.2 Key-on, engine off** — clean boot, sensors at expected at-rest values
- **2.3 Engine cranking + start** — survives crank dip, RPM received, engine-running gate activates
- **2.4 TunerStudio integration** — all 5 channels live and matching BLE console
- **2.5 Drive cycle datalog** — cold-start, cruise, boost event, idle/decel — clean data, no resets, no false faults
- **2.6 CAN bus coexistence** — wideband still readable, no collisions
- **2.7 OTA from driveway** — DEFERRED to v4.x (§6.4); not run for v4

### 8.3 Phase 3: Ongoing

- Periodic CAN bus inspection (USB-CAN dongle)
- Long-term datalog review for drift, sensor degradation
- Calibration check at oil change intervals

### 8.4 Test tooling required

| Item | Approx cost |
|------|-------------|
| USB-CAN dongle (CANable v2 or similar) | $25 |
| Adjustable bench supply | already on hand |
| 10 kΩ potentiometer + jumper wires | already on hand |
| Phone with `Serial Bluetooth Terminal` | already on hand |
| **Total new outlay** | ~$25 |

---

## 9. Open Items / Bench-Verify

The following items are placeholders in the design that need bench verification before flashing the production firmware:

### Hardware/sensor confirmation
1. **MCP2515 crystal frequency** — ✅ resolved. Actual hardware is 16 MHz (`Y16.000H049`); `config.h` updated to `MCP_16MHZ`.
2. **Pressure sensor output type** — verify whether the 0–100 PSI gauge sensors are 0–5V or 0.5–4.5V. Set conversion constants accordingly.
3. **Bosch 0 261 230 146 transfer function** — verify against actual datasheet at first power-up. Inferred placeholder: `kPa_abs ≈ 24.7 × V + 0.12`. Calibrate against known atmospheric pressure on bench.
4. **Post-SC IAT (GM open-element) curve** — verify R₂₅ and β against the actual part purchased.

### Firmware prototyping required
5. **R4 wireless OTA viability** — DEFERRED to v4.x; current v4 answer is USB-cable updates only (§6.4).
6. **`OTAUpdate::update(file_path)` working with a locally-staged file** — DEFERRED to v4.x with wireless OTA (§6.4).
7. **BLE command fragmentation/MTU behavior** — test with `Serial Bluetooth Terminal` (Android) and `nRF Connect` (iOS/Android) to confirm the 19-byte chunk + reassembly behavior is transparent to the user.
8. **ConnectivityFirmware version on R4's ESP32-S3** — confirm running v0.6.0+ for stable BLE.

### In-car physical/integration verification
9. **CAN bus physical layer** — verify the option-port CAN bus has proper 120Ω termination and a clean ground reference. Check error counters on MS3 side after install. Bus-level integrity issues will manifest as TXERR/RXERR escalation that no amount of firmware tuning can fix.
10. **Crank/brownout behavior of the entire power chain** — verify the buck-boost holds up through a cold-crank dip without browning out the Carduino. Test by cranking the engine and watching for spurious resets in the boot counter.
11. **Sensor ground-offset noise with engine running** — verify there's no measurable ground-loop voltage between the Carduino's GND and the sensor case grounds when the engine is running and the alternator is loaded. Pressure sensors sharing the 5V rail are most exposed if there's a ground offset between the Carduino's reference and the engine block.

### Recovery behaviors
12. **Interrupted OTA upload** — DEFERRED to v4.x with wireless OTA (§6.4).
13. **Corrupted image apply** — DEFERRED to v4.x with wireless OTA (§6.4).

---

## 10. Future scope (v5+)

- Receive-side CAN parsing for general MS3 broadcast consumption (CLT, MAT, MAP, TPS)
- Derived calculations (intercooler effectiveness, compressor efficiency, filter restriction trends)
- Digital outputs (relay control, alarm LEDs)
- Persistent fault journal with rotation
- Native RA4M1 CAN peripheral with external transceiver (replaces MCP2515 shield)
- Web dashboard, MQTT publishing, Home Assistant integration
- 6th sensor on A5 (TBD)

---

## 11. Implementation Scaffolding

### 11.1 Toolchain & versions

| Item | Version / Source |
|------|------------------|
| Arduino IDE | 2.3.x or newer (preferred for development) |
| `arduino-cli` | 1.x (preferred for repeatable CLI builds and CI scripting) |
| **Board package:** Arduino Renesas UNO Boards | Pin to a specific version, e.g., `1.4.x` (record exact version in repo README before first build) |
| Board target | `arduino:renesas_uno:unor4wifi` |
| ESP32-S3 ConnectivityFirmware (on the R4 itself) | **v0.6.0 minimum** for stable BLE (per codex review §3) |

### 11.2 Library dependencies (with version pinning)

| Library | Version | Source |
|---------|---------|--------|
| `ArduinoBLE` | latest stable on R4 (≥1.3.x) | Arduino IDE Library Manager |
| `Arduino_LED_Matrix` | shipped with Renesas core | core-bundled |
| `OTAUpdate` | deferred to v4.x wireless update work (§6.4) | core-bundled |
| `WiFiS3` | deferred to v4.x wireless update work (§6.4) | core-bundled |
| `mcp2515` (autowp/arduino-mcp2515) | 0.5.x (latest as of writing) | https://github.com/autowp/arduino-mcp2515 |

**Pin everything in `library.properties` or a `libraries.txt` manifest** at the project root. Track exact versions in git so a fresh checkout reproduces the same build.

### 11.3 Repository file layout

```
projects/carduino-v4/
├── DESIGN.md                       # This file
├── README.md                       # Build / flash / wiring quickstart
├── secrets.h.template              # Template for secrets.h
├── secrets.h                       # gitignored — v4.x wireless update secrets, if needed
├── libraries.txt                   # Pinned library versions
├── carduino-v4/                    # Arduino sketch root
│   ├── carduino-v4.ino             # setup() / loop() — minimal, dispatches to phases
│   ├── config.h                    # All tunable constants (pin map, EWMA α, thresholds)
│   ├── sensor_pipeline.h/.cpp      # ADC read, EWMA, Steinhart-Hart, conversions
│   ├── sensor_health.h/.cpp        # Per-sensor fault state machines, debounce
│   ├── can_protocol.h/.cpp         # MCP2515 init, frame pack/send, RX filter for ID 1512
│   ├── system_health.h/.cpp        # MCP2515 errors, loop timing, persistent state
│   ├── ble_console.h/.cpp          # NUS peripheral, command parser, periodic dump
│   ├── display_matrix.h/.cpp       # LED matrix state machine (boot, normal, maint, error)
│   ├── maintenance_mode.h/.cpp     # deferred to v4.x wireless update work (§6.4)
│   └── self_tests.h/.cpp           # Boot self-tests, ERR## codes
├── prototypes/                     # Standalone sketches for risk validation
│   ├── ota_proto/                  # OTAUpdate library API exploration
│   ├── ble_proto/                  # NUS peripheral skeleton
│   └── can_loopback/               # MCP2515 self-test before integration
└── docs/
    ├── tunerstudio-setup.md        # Step-by-step MS3 receiver config
    ├── wiring-diagram.md           # Pin-by-pin sensor wiring
    └── bench-test-procedures.md    # Phase 1 test scripts
```

**Why split this way:**
- Each `.h/.cpp` pair has one clear responsibility (~200-400 lines each, fits in head)
- `config.h` is the single-source-of-truth for all tunables — recalibration = edit + reflash + commit
- `prototypes/` lets us validate risky pieces (OTA, BLE) without contaminating the production sketch
- `docs/` stays in-tree so the spec, wiring, and TS config travel with the code

### 11.4 Build & flash workflow

**Bench/development (primary):**
```
arduino-cli compile --fqbn arduino:renesas_uno:unor4wifi carduino-v4/
arduino-cli upload  --fqbn arduino:renesas_uno:unor4wifi --port /dev/ttyACM0 carduino-v4/
```
or equivalent in the Arduino IDE 2.x.

**Production (in-car):**
- v4 production firmware updates are USB-cable only (§6.4).
- Wireless update workflow is deferred to v4.x (§6.4.3).

**Building a `.bin` for future wireless OTA (deferred to v4.x):**
```
arduino-cli compile --fqbn arduino:renesas_uno:unor4wifi --output-dir build carduino-v4/
# build/carduino-v4.ino.bin is the upload artifact
```

### 11.5 Build-time configuration

A `secrets.h` file (gitignored) is reserved for future v4.x wireless update work:
```c
// v4 does not require wireless update credentials (§6.4).
```

A `secrets.h.template` is committed to the repo as a starting point.

### 11.6 Recommended build order

To stage risk and produce intermediate working artifacts:

| Stage | What to build | Validation |
|-------|--------------|------------|
| **1.** | Bare super-loop, sensor read + serial print over USB | Pot injection, see values change over USB serial |
| **2.** | Add MCP2515 init + Frame 1 TX | USB-CAN dongle captures Frame 1 with correct values |
| **3.** | Add Frame 2 TX + sequence counter + health bitmask (stubbed) | All bytes match spec |
| **4.** | Add EWMA filter + Steinhart-Hart conversions | Frame values are in engineering units |
| **5.** | Add real per-sensor fault detection + debounce | Disconnect sensor → fault asserts within 50 ms; reconnect → clears |
| **6.** | Add LED matrix display state machine | Boot, normal, error states all display correctly |
| **7.** | Add BLE NUS peripheral + periodic dump + commands | Phone connects, sees data, can issue commands |
| **8.** | Add boot self-tests + persistent state (reset cause, boot counter) | Forced failures display correct ERR##; boot counter survives reset |
| **9.** | Add CAN RX filter for ID 1512 (RPM) + engine-running gate | Flatline detection only fires when RPM > 500 |
| **10.** | Prototype wireless OTA in standalone sketch (deferred to v4.x) | See §6.4 |
| **11.** | Integrate OTA + maintenance mode (deferred to v4.x) | See §6.4 |

Stages 1-9 produce a functional in-car device with USB-cable updates. Stages 10-11 are deferred to v4.x (§6.4).

---

## Appendix A: References

- [Arduino Uno R4 WiFi datasheet](https://docs.arduino.cc/resources/datasheets/ABX00087-datasheet.pdf)
- [Arduino Renesas core (`WiFiS3`, `OTAUpdate`)](https://github.com/arduino/ArduinoCore-renesas)
- [autowp/arduino-mcp2515 library](https://github.com/autowp/arduino-mcp2515)
- [Renesas RA4M1 hardware manual](https://www.renesas.com/en/document/mah/renesas-ra4m1-group-users-manual-hardware)
- [Microchip MCP2515 datasheet](https://ww1.microchip.com/downloads/en/DeviceDoc/MCP2515-Family-Data-Sheet-DS20001801K.pdf)
- v3 source: `projects/CARDUINO_v3/`
- Project memory: `personal/miata/CLAUDE.md`
- MS3Pro PNP capabilities: `personal/miata/knowledge/ms3/ms3pro-pnp-capabilities.md`

## Appendix B: Decision log

Cross-reviewed with codex on five occasions during design:

1. **Architecture choice** — initially proposed FreeRTOS dual-task split for an assumed ESP32. After discovering hardware was actually Uno R4 WiFi (single-core), pivoted to super-loop. Codex confirmed correctness for the platform.
2. **Sensor pipeline** — codex flagged EWMA α=0.20 as too responsive for noisy automotive senders (revised to 0.10), confirmed Bosch 0 261 230 146 is 3-pin MAP not 4-pin TMAP, recommended fault debounce + flatline detection.
3. **Comms architecture (BLE vs WiFi)** — codex confirmed ArduinoBLE on R4 is mature enough for NUS-style serial console (with v0.6.0+ ConnectivityFirmware), but BLE OTA on R4 is "you'll regret this" (no maintained library; app runs on RA4M1 not ESP32-S3). Wireless OTA was later deferred to v4.x (§6.4).
4. **Error handling** — codex corrected MCP2515 TXERR > 96 as warning-only (not failure), recommended persistent reset cause + boot counter, suggested fault-class-specific debounce, flagged 30-sec maintenance watchdog as overgenerous (revised to 8 sec).
5. **Final design review** — codex caught D13 pin map conflict (SPI SCK can't double as heartbeat LED, removed); flagged OTA flow as overstated (downgraded to "design intent / open risk"); identified missing BLE protocol details (added §6.7 with UUIDs, MTU, fragmentation, line conventions); identified missing implementation scaffolding (added §11 with toolchain, libraries, file layout, build/flash workflow, staged build order); expanded §9 bench-verify list with crank/brownout, CAN physical-layer, BLE MTU testing, ground-offset noise, and OTA recovery cases.
