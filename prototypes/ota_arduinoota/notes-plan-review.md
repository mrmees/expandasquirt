# Plan Review — codex critical pass

Review date: 2026-05-05
Plan reviewed: `IMPLEMENTATION-PLAN.md` Phases L-P (Tasks 53-80)
Spec reviewed against: `V4X-DESIGN.md`

This review was conducted under read-only sandbox; Claude saved the output to this file post-review.

## A. Plan-vs-spec Coverage Gaps

The self-review is mostly accurate, but not fully comprehensive.

| Design section | Plan coverage | Gaps / issues |
|---|---|---|
| §3 System architecture (`V4X-DESIGN.md:30`, `:52`) | Tasks 53-55 prove the transport assumptions; Tasks 56-62 firmware; Tasks 63-76 app. | Real architecture depends on scoped LOH network routing, but Task 71 explicitly leaves `network = null` (`IMPLEMENTATION-PLAN.md:6367-6380`). That weakens §3.2 and §4.4 coverage. |
| §4.1 Screens (`V4X-DESIGN.md:77`) | Tasks 66, 67, 69, 74, 76. | Covered. |
| §4.2 State / persistence (`V4X-DESIGN.md:101-110`) | Task 67 creates `KnownDevice` and current MAC (`IMPLEMENTATION-PLAN.md:5999-6034`). | It defines `lastKnownVersion` / `lastSeenEpochMs`, but I do not see a task that updates them after connect or OTA success. Task 74 reaches `Done(newVersion)` (`:6577`, `:6642-6644`) but does not write it back to `DeviceStore`. |
| §4.3 BLE central behavior (`V4X-DESIGN.md:112-120`) | Tasks 64, 65, 68. | Missing runtime permission flow for production app. Manifest permissions are listed (`IMPLEMENTATION-PLAN.md:5510-5516`), but unlike Task 54 prototype (`:4502-4505`), Phase N does not add runtime grant handling before BLE scan/connect. |
| §4.4 LOH lifecycle (`V4X-DESIGN.md:122-134`) | Tasks 71-73. | Incomplete. Task 71 says the underlying `Network` is needed but leaves it for "next pass" (`IMPLEMENTATION-PLAN.md:6380`). Task 72 uses plain `NsdManager.discoverServices` with no LOH `Network` scoping (`:6412-6439`), despite the spec requiring scoped discovery (`V4X-DESIGN.md:129`, `:320`). |
| §4.5 OTA HTTP push (`V4X-DESIGN.md:136`) | Task 73. | Mostly covered, but Task 73 only uses `socketFactory` if `network != null` (`IMPLEMENTATION-PLAN.md:6501`), and Task 71 currently returns null network. |
| §5.1 BLE maintenance commands (`V4X-DESIGN.md:170`) | Tasks 57-58. | Covered. |
| §5.2 Banner version token (`V4X-DESIGN.md:198`) | Task 59 (`IMPLEMENTATION-PLAN.md:5101-5105`). | Covered. |
| §5.3 Maintenance state machine (`V4X-DESIGN.md:210`) | Task 61. | Mostly covered. Callback API uncertainty is explicitly noted (`IMPLEMENTATION-PLAN.md:5335-5343`). |
| §5.4 CAN final-status frame (`V4X-DESIGN.md:225`) | Task 60 and Task 61 entry action (`IMPLEMENTATION-PLAN.md:5146-5169`, `:5229-5232`). | Covered. |
| §5.5 No new EEPROM records (`V4X-DESIGN.md:229`) | Self-review says implicit. | Acceptable. No plan task touches firmware persistence. |
| §5.6 Sketch size impact (`V4X-DESIGN.md:233-241`) | Task 70 checks selected app file size (`IMPLEMENTATION-PLAN.md:6260-6271`). | Missing a final firmware compile size check after Tasks 56-62. The spec explicitly says to verify post-implementation and cut features if overflow occurs. |
| §6 OTA wire protocol (`V4X-DESIGN.md:245`) | Task 53 server prototype; Task 73 client. | Covered. |
| §7 Failure modes (`V4X-DESIGN.md:251-263`) | Tasks 70-74. | "HTTP push outcome unknown" is not handled as specified. Task 74 maps `NetworkError` directly to failure (`IMPLEMENTATION-PLAN.md:6623-6627`) instead of scanning BLE first to determine old/new/no firmware state. |
| §8 USB rescue (`V4X-DESIGN.md:266`) | Task 76. | Screen is covered, but §11 also says bootloader-force procedure must be verified on Matthew's clone (`V4X-DESIGN.md:321`). I do not see a task that actually verifies that before shipping the screen. |
| §9 Out of scope (`V4X-DESIGN.md:282`) | Mostly respected. | No major issue. |
| §10 Implementation phasing (`V4X-DESIGN.md:298`) | Phases L-P match the rough phasing. | Covered. |
| §11 Open verification items (`V4X-DESIGN.md:315-324`) | Phase L covers the two big prototypes. | Not all items are covered: scoped `NsdManager` on LOH network is not actually implemented; bootloader-force verification is missing; final sketch size verification is missing. |

## B. Task Ordering and Dependencies

The high-level dependency order in the self-review is mostly right (`IMPLEMENTATION-PLAN.md:7018-7028`), but it misses several practical blockers.

1. Android flow is ordered correctly on paper: Task 64 BLE client → Task 65 parser → Task 66 dashboard → Task 67 picker/store → Task 68 autoconnect → Task 69 diagnostics → Tasks 70-74 OTA. The issue is not order; it is missing infrastructure. Runtime permissions should happen before Task 64/67 scanning and connecting. Manifest-only setup at `IMPLEMENTATION-PLAN.md:5510-5516` is not enough on modern Android.

2. Task 71 does not produce the `Network` that Task 73 depends on. Task 71 returns `LohSession(... network = null)` (`IMPLEMENTATION-PLAN.md:6367-6370`) and says to add a `ConnectivityManager.NetworkCallback` "next pass" (`:6380`). Task 73 then conditionally uses `network.socketFactory` only if non-null (`:6501`). That means the dependency chain claims Task 71 blocks Task 73, but Task 71 does not actually satisfy Task 73's routing requirement.

3. Task 72 is ordered after Task 71, but it does not consume the LOH network from Task 71. It calls `nsd.discoverServices(...)` globally (`IMPLEMENTATION-PLAN.md:6439`). This conflicts with the design's "scoped to the LOH network" requirement (`V4X-DESIGN.md:129`, `:320`).

4. Task 74 assumes `ble.writeLine("maintenance ...")`, then blindly delays 8 seconds (`IMPLEMENTATION-PLAN.md:6608-6611`). It does not actually wait for `OK maintenance armed timeout=3000` or BLE disconnect, even though the comment says it does. This can fail on first use if the command is rejected or the write never completes.

5. Firmware Phase M flow is generally coherent: Task 56 library → Task 57 percent decode/RX buffer → Task 58 BLE commands → Task 59 version banner → Task 60 CAN marker → Task 61 state machine → Task 62 LED patterns. Task 60's marker call is correctly repeated inside Task 61's `ARMED` entry (`IMPLEMENTATION-PLAN.md:5229-5232`).

6. Firmware callback dependency is explicitly risky but acceptable as a flagged implementation check. Task 61 uses `ArduinoOTA.onStartCallback = ...` and `onErrorCallback = ...` (`IMPLEMENTATION-PLAN.md:5335-5339`) and notes the API may be setter methods (`:5343`). That should be moved from a note into a concrete verification step before coding the state machine.

## C. Granularity and Concreteness — Random Sample

### Firmware sample: Task 61

Task 61 is mostly executable. It defines states (`IMPLEMENTATION-PLAN.md:5200-5208`), entry actions (`:5228-5255`), tick transitions (`:5280-5316`), callback wiring (`:5330-5343`), and integration into `loop()` (`:5345-5357`).

Gaps:

- "If maintenance is active, the existing sensor/CAN/BLE phases should be gated to skip work" (`IMPLEMENTATION-PLAN.md:5357`) is too hand-wavy for a task that modifies the main loop. It should say exactly which scheduler blocks are skipped and whether `maintenance_tick()` runs before or after display/watchdog servicing.
- The callback API uncertainty at `IMPLEMENTATION-PLAN.md:5343` is real. The task can be executed, but the implementer will need to inspect JAndrassy headers before writing that part.

### Android sample: Task 71

Task 71 is not concrete enough to execute correctly.

It creates `startLoh()` and reads SSID/passphrase, but explicitly returns `network = null` (`IMPLEMENTATION-PLAN.md:6367-6370`). Then the note says getting the underlying `Network` requires a `ConnectivityManager.registerNetworkCallback()` and to "add that next pass" (`:6380`). There is no later task that actually implements that missing network capture. Since §4.4 requires the HTTP request to be scoped to the LOH network (`V4X-DESIGN.md:134`), this is a real plan gap, not polish.

### Cleanup sample: Task 79

Task 79 is mostly concrete: update old Phase J header, update `DESIGN.md` §6.4.3, update §6.5 patterns, commit (`IMPLEMENTATION-PLAN.md:6907-6945`).

One weak spot: Step 3 includes "Applying: solid top row + bottom row (or whatever the final pattern is)" (`IMPLEMENTATION-PLAN.md:6935-6941`). That is fine for a note during design, but not for an executable cleanup task. It should point directly to the final Task 62 names/patterns.

## D. YAGNI / Overengineering Check

1. Task 64 MTU splitting is not YAGNI, but the proposed implementation is wrong-shaped. The maintenance command can exceed the default 20-byte BLE payload, so splitting earns its keep. The problem is hard-coding `val mtu = 240` (`IMPLEMENTATION-PLAN.md:5603-5604`) and issuing writes in a loop without waiting for write callbacks (`:5606-5611`). A simpler reliable approach is to write newline-framed chunks at 20 bytes or track the negotiated MTU and serialize writes through `onCharacteristicWrite`.

2. Task 67's DataStore JSON list is acceptable, but the UI around it is more than Matthew needs. The spec asks for a "multi-aware data model with single-device UX" (`V4X-DESIGN.md:103-109`), so JSON-in-Preferences is a reasonable lightweight tool. Room would be overkill. The overbuilt part is the managed known/nearby picker with rename, forget, long-press, last-seen, and last-known version (`IMPLEMENTATION-PLAN.md:5999-6034`, `:6075-6079`) when one stored current MAC + nickname would cover the hobby use case.

3. Task 74's sealed `OtaStep` state model is not over-elaborate. The nine states map directly to real user-visible phases and failure handling (`IMPLEMENTATION-PLAN.md:6569-6578`). The issue is behavioral correctness, not abstraction size.

4. Task 54 committing a throwaway Android Studio project under `app/proto-loh/` is probably unnecessary repo churn (`IMPLEMENTATION-PLAN.md:4445-4447`, `:4603-4608`). For this project, the notes/results matter more than preserving the disposable prototype.

5. Task 75's five OTA cycles are not enterprise-grade excess. Given the OTA path can force USB rescue on bad timing, five bench repetitions are a reasonable hobby-level safeguard (`IMPLEMENTATION-PLAN.md:6745-6752`).

## E. Risk Items the Plan Glosses Over

1. Runtime Android permissions are likely to bite immediately. The production app lists `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, etc. (`IMPLEMENTATION-PLAN.md:5510-5516`) but does not implement runtime grant UX. Task 54 prototype does request some permissions (`:4502-4505`), but Phase N/O does not carry that forward.

2. LOH `Network` capture is the biggest app risk. Task 71 acknowledges the problem and leaves `network = null` (`IMPLEMENTATION-PLAN.md:6367-6380`). Task 73 only binds if network exists (`:6501`). This can make OkHttp route over cellular/default network instead of the local hotspot.

3. mDNS discovery is not scoped to the LOH network. Task 72 uses global `NsdManager.discoverServices` (`IMPLEMENTATION-PLAN.md:6439`), while the design explicitly requires LOH-scoped discovery (`V4X-DESIGN.md:129`, `:320`). On Android this is exactly the kind of thing that may work on the bench laptop and fail on the phone.

4. BLE write timing is under-specified. Task 64 writes chunks in a tight loop (`IMPLEMENTATION-PLAN.md:5606-5611`). Android GATT writes are asynchronous; without waiting for `onCharacteristicWrite`, later chunks may fail or be dropped. This matters for the long `maintenance ssid=... psk=... pwd=...` command.

5. Task 74 does not wait for the maintenance ACK. It sends the command and delays 8 seconds (`IMPLEMENTATION-PLAN.md:6608-6611`). If firmware returns `ERR maintenance bad-args`, `ERR maintenance busy`, or the BLE write fails, the app still proceeds to mDNS and fails later with a misleading hotspot/device error.

6. The OTA verification test artifact is inconsistent. Task 74 Step 4 says to pick Task 53's `test-sketch` and expect the app to report the new version (`IMPLEMENTATION-PLAN.md:6725-6727`), but Task 53's test sketch only prints serial text and has no BLE banner/version implementation (`IMPLEMENTATION-PLAN.md:4348-4355`). That test cannot verify the app's BLE banner path as written.

7. Unknown HTTP outcome is flattened into failure. The design says a read timeout after sending the body should scan BLE and inspect the banner before retry/rescue (`V4X-DESIGN.md:259`). Task 74 maps `OtaResult.NetworkError` directly to `fail(...)` (`IMPLEMENTATION-PLAN.md:6623-6627`).

8. Final firmware size is not gated after adding ArduinoOTA. The design warns the headroom is tight and says to verify post-implementation (`V4X-DESIGN.md:233-241`). Task 56 compiles before including the library (`IMPLEMENTATION-PLAN.md:4680-4685`), but there is no explicit post-Task-61 size check.

9. ArduinoOTA callback API is a real implementation risk, not just a footnote. Task 61 uses direct lambda assignment (`IMPLEMENTATION-PLAN.md:5335-5339`) while also saying the API may be setter methods (`:5343`). Put the header check before writing callback-dependent state transitions.

10. Bootloader rescue instructions need hardware verification. The design requires verifying the bootloader-force procedure on Matthew's R4 clone (`V4X-DESIGN.md:321`). Task 76 writes the screen (`IMPLEMENTATION-PLAN.md:6783-6838`) but does not include a verification step.

---

## Resolution status (post-review)

All 11 must-fix items addressed inline in `IMPLEMENTATION-PLAN.md`. See the "Codex review findings" subsection of the v4.x Self-Review for the per-item resolution. Item D2 (Task 67 picker UI bloat) was also trimmed. Item D4 (Task 54 throwaway Android project) was deliberately retained — the cosmetic cost of `~50 lines of build files in repo` was judged not worth re-litigating.
