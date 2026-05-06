# TunerStudio Setup for EXPANDASQUIRT v4

Field names in this doc match TunerStudio 3.x. Newer versions may rename fields
slightly; the navigation paths and semantic intent stay constant.

EXPANDASQUIRT v4 transmits two standard 8-byte CAN frames at 500 kbps. The MS3
project receives the five sensor channels as CAN ADC inputs, each encoded as an
unsigned 16-bit big-endian value in engineering units times 10.

## Prerequisites

- TunerStudio 3.x or newer.
- MS3Pro PNP project loaded.
- EXPANDASQUIRT v4 connected to the MS3 CAN bus through the MCP2515 shield.
- CAN bus configured for 500 kbps, matching `DESIGN.md` section 5.1.

## CAN Receiving

Navigate to **CAN Bus / Testmodes -> CAN Receiving**.

Configure these CAN ADC inputs:

| CAN ADC | ID | offset | size | EXPANDASQUIRT field | Units on wire |
|---------|----|--------|------|----------------|---------------|
| CAN ADC01 | 1025 | 0 | B2U | Oil temperature | deg F x 10 |
| CAN ADC02 | 1025 | 2 | B2U | Oil pressure | PSI x 10 |
| CAN ADC03 | 1025 | 4 | B2U | Fuel pressure | PSI x 10 |
| CAN ADC04 | 1025 | 6 | B2U | Pre-supercharger pressure | kPa x 10 |
| CAN ADC05 | 1026 | 0 | B2U | Post-supercharger air temperature | deg F x 10 |

`B2U` is the required size because EXPANDASQUIRT sends each sensor as a 2-byte
unsigned big-endian value. Do not map Frame 2 bytes 2-7 as sensor channels:
those bytes carry reserved data, sequence counter, health bitmask, status flags,
and max held-value age.

For reference, the transmitted frame layout is:

| Frame | ID | Bytes | Field |
|-------|----|-------|-------|
| Frame 1 | 1025 (`0x401`) | 0-1 | Oil temperature, deg F x 10 |
| Frame 1 | 1025 (`0x401`) | 2-3 | Oil pressure, PSI x 10 |
| Frame 1 | 1025 (`0x401`) | 4-5 | Fuel pressure, PSI x 10 |
| Frame 1 | 1025 (`0x401`) | 6-7 | Pre-supercharger pressure, kPa x 10 |
| Frame 2 | 1026 (`0x402`) | 0-1 | Post-supercharger air temperature, deg F x 10 |
| Frame 2 | 1026 (`0x402`) | 2-3 | Reserved, `0xFFFF` |
| Frame 2 | 1026 (`0x402`) | 4 | Sequence counter |
| Frame 2 | 1026 (`0x402`) | 5 | Health bitmask |
| Frame 2 | 1026 (`0x402`) | 6 | Status flags |
| Frame 2 | 1026 (`0x402`) | 7 | Max held-value age, 100 ms units |

## Generic Sensor Inputs

Navigate to **Advanced Engine -> Generic Sensor Inputs**.

Map each CAN ADC input to a generic sensor channel:

| Sensor channel | Source | Display unit | Divisor |
|----------------|--------|--------------|---------|
| Oil temperature | CAN ADC01 | deg F | 10 |
| Oil pressure | CAN ADC02 | PSI | 10 |
| Fuel pressure | CAN ADC03 | PSI | 10 |
| Pre-supercharger pressure | CAN ADC04 | kPa | 10 |
| Post-supercharger air temperature | CAN ADC05 | deg F | 10 |

The divisor is 10 for every EXPANDASQUIRT sensor because the firmware sends one
decimal place as an integer.

## Datalog Channels

Add the mapped generic sensor channels to the active datalog template:

- Oil temperature
- Oil pressure
- Fuel pressure
- Pre-supercharger pressure
- Post-supercharger air temperature

## EXPANDASQUIRT Receive Frame

EXPANDASQUIRT listens for the MS3 dash broadcast group on CAN ID 1512 (`0x5E8`).
Bytes 2-3 carry RPM as `uint16` big-endian. The firmware uses that RPM value as
the primary engine-running gate for flatline detection; no TunerStudio Generic
Sensor Input mapping is required for this receive path.
