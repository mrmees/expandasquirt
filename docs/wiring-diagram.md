# CARDUINO v4 Wiring

Pin assignments in this document use `carduino-v4/config.h` as canonical where
firmware constants exist. They match `DESIGN.md` section 2.3.

## Power

```
Switched 12V, key-on
    |
    v
Buck-boost regulator, Amazon B07WY4P7W8
    |  output: regulated 12V, up to 3A
    v
1A polyfuse
    |
    v
Uno R4 VIN
    |  onboard regulator creates the 5V rail
    +-- MCP2515 CAN shield 5V
    +-- sensor 5V supplies
    +-- thermistor pull-ups

R4 GND
    |
    +-- buck-boost GND
    +-- sensor grounds
    +-- engine ground, single point
```

The electronics should be mounted in the cabin or under the dash. `DESIGN.md`
section 2.5 notes that the buck-boost operating range is -40 to +80 deg C, so
engine-bay heat soak can exceed the regulator rating.

All sensors share the R4 5V rail. The pressure sensors are ratiometric and the
R4 ADC reference is the same 5V supply, so supply movement cancels in the
conversion math.

## Sensor Wiring

Each ADC input gets a 100 nF ceramic capacitor from the signal pin to GND,
mounted near the R4.

| Sensor | Type | R4 pin | Pull-up | ADC noise cap |
|--------|------|--------|---------|---------------|
| Oil temperature | NTC thermistor, 10k ohm, B=3950 | A0 (`PIN_OIL_TEMP`) | 10k ohm to 5V (`OIL_TEMP_PULLUP_OHMS`) | 100 nF ceramic to GND |
| Post-supercharger air temperature | GM open-element NTC | A1 (`PIN_POST_SC_TEMP`) | 2.49k ohm to 5V (`POST_SC_TEMP_PULLUP_OHMS`) | 100 nF ceramic to GND |
| Oil pressure | 0-5V ratiometric, 0-100 PSI | A2 (`PIN_OIL_PRESS`) | none | 100 nF ceramic to GND |
| Fuel pressure | 0-5V ratiometric, 0-100 PSI | A3 (`PIN_FUEL_PRESS`) | none | 100 nF ceramic to GND |
| Pre-supercharger pressure | Bosch 0 261 230 146, 3-pin MAP | A4 (`PIN_PRE_SC_PRESS`) | none | 100 nF ceramic to GND |
| Reserved | Future expansion | A5 | none | none specified |

Thermistors use a voltage divider: pull-up resistor from signal to 5V,
thermistor from signal to GND. Pressure sensors connect directly to the ADC
input; no voltage divider is used.

## CAN Shield

The Keyestudio EF02037 MCP2515 CAN-Bus Shield seats directly on the Uno R4
headers. The shield uses 5V SPI and does not require level shifting.

| Signal | R4 pin | Source |
|--------|--------|--------|
| MCP2515 INT | D2 (`PIN_MCP2515_INT`) | `config.h` |
| MCP2515 CS | D10 (`PIN_MCP2515_CS`) | `config.h` |
| SPI MOSI | D11 | `DESIGN.md` section 2.3 |
| SPI MISO | D12 | `DESIGN.md` section 2.3 |
| SPI SCK | D13 | `DESIGN.md` section 2.3 |

D13 is occupied by SPI SCK while CAN is active; status display uses the onboard
12x8 LED matrix instead of the D13 LED.

Connect shield `CAN_H` to the MS3 option-port `CAN_H`, and shield `CAN_L` to
the option-port `CAN_L`. Verify 120 ohm termination on the CAN bus before
powering the installed system.

## Text Schematic

```
                         +-----------------------------+
Switched 12V key-on ---->| Buck-boost B07WY4P7W8       |
Vehicle GND ------------>| IN-/GND                 OUT |---- 12V regulated
                         +-----------------------------+
                                                     |
                                                     v
                                               1A polyfuse
                                                     |
                                                     v
                  +-----------------------------------------+
                  | Freenove FNK0096 / Uno R4 WiFi clone   |
                  |                                         |
12V regulated --->| VIN                                     |
Ground ---------->| GND                                     |
                  |                                         |
5V rail ----------+--- 10k ohm ---+--- A0 oil temp ---------+--- 100 nF --- GND
                                  |                         |
                                  +--- oil temp NTC --------+--- GND
                  |                                         |
5V rail ----------+--- 2.49k ohm -+--- A1 post-SC temp -----+--- 100 nF --- GND
                                  |                         |
                                  +--- GM NTC --------------+--- GND
                  |                                         |
Oil pressure 0-5V -------------------- A2 ------------------+--- 100 nF --- GND
Fuel pressure 0-5V ------------------- A3 ------------------+--- 100 nF --- GND
Bosch MAP signal --------------------- A4 ------------------+--- 100 nF --- GND
Sensor grounds ----------------------- GND
Sensor 5V supplies ------------------- 5V rail
                  |                                         |
                  | D2  <--- MCP2515 INT                   |
                  | D10 ---> MCP2515 CS                    |
                  | D11 ---> SPI MOSI                      |
                  | D12 <--- SPI MISO                      |
                  | D13 ---> SPI SCK                       |
                  +-------------------+---------------------+
                                      |
                                      v
                         +-----------------------------+
                         | EF02037 MCP2515 CAN shield |
                         | CAN_H ---- MS3 CAN_H        |
                         | CAN_L ---- MS3 CAN_L        |
                         +-----------------------------+
```
