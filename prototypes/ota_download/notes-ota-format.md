# UNO R4 WiFi `.ota` format and conversion recipe

Research date: 2026-05-04

## Short answer

The UNO R4 WiFi sketch OTA file is the Arduino `Arduino_ESP32_OTA` container format:

1. LZSS-compress the UNO R4 WiFi sketch `.bin`.
2. Prefix the compressed payload with an Arduino OTA header.
3. Use the UNO R4 WiFi magic number `0x23411002`.

The canonical conversion tools are in `arduino-libraries/ArduinoIoTCloud`, under `extras/tools`:

- `lzss.py` / `lzss.c` for compression.
- `bin2ota.py` for the OTA header.

Use:

```bash
git clone https://github.com/arduino-libraries/ArduinoIoTCloud.git
cd ArduinoIoTCloud/extras/tools
python3 -m pip install crccheck
./lzss.py --encode /path/to/carduino-v4.ino.bin carduino-v4.ino.lzss
./bin2ota.py UNOR4WIFI carduino-v4.ino.lzss carduino-v4.ino.ota
```

For this project, replacing `/path/to/...` with the known build output:

```bash
./lzss.py --encode /mnt/e/claude/personal/miata/projects/carduino-v4/carduino-v4/build/arduino.renesas_uno.unor4wifi/carduino-v4.ino.bin carduino-v4.ino.lzss
./bin2ota.py UNOR4WIFI carduino-v4.ino.lzss carduino-v4.ino.ota
```

The command above is written for Linux/WSL/macOS from inside `ArduinoIoTCloud/extras/tools`. The upstream tool directory ships `lzss.so` and `lzss.dylib`; I did not find an upstream-documented native Windows command for `lzss.py`.

## Container layout

Confirmed from `arduino-libraries/Arduino_ESP32_OTA`:

```c
union OtaHeader {
  struct __attribute__((packed)) {
    uint32_t len;
    uint32_t crc32;
    uint32_t magic_number;
    HeaderVersion hdr_version;
  } header;
  uint8_t buf[sizeof(header)];
  static_assert(sizeof(buf) == 20, "Error: sizeof(HEADER) != 20");
};
```

`HeaderVersion` is 8 bytes:

```c
struct __attribute__((packed)) {
  uint32_t header_version    :  6;
  uint32_t compression       :  1;
  uint32_t signature         :  1;
  uint32_t spare             :  4;
  uint32_t payload_target    :  4;
  uint32_t payload_major     :  8;
  uint32_t payload_minor     :  8;
  uint32_t payload_patch     :  8;
  uint32_t payload_build_num : 24;
};
```

`bin2ota.py` writes all multibyte fields little-endian:

| Offset | Size | Field | UNO R4 WiFi value / meaning |
| --- | ---: | --- | --- |
| `0x00` | 4 | `len` | Length of `magic_number + hdr_version + compressed_payload` |
| `0x04` | 4 | `crc32` | CRC32 over `magic_number + hdr_version + compressed_payload` |
| `0x08` | 4 | `magic_number` | `0x23411002`, emitted as bytes `02 10 41 23` |
| `0x0c` | 8 | `hdr_version` | `00 00 00 00 00 00 00 40` |
| `0x14` | n | payload | LZSS-compressed sketch `.bin` |

The version bytes used by `bin2ota.py` are:

```python
version = bytearray([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40])
```

The source comment says this is "all 0 except the compression flag set." In the `HeaderVersion` bitfield, this means compression is enabled and signature is not enabled.

## Verification/apply path on UNO R4 WiFi

The Renesas-side `OTAUpdate` Arduino library is only an AT-command wrapper. For sketch updates it sends commands to the UNO R4 WiFi ESP32-S3 bridge firmware:

- `OTAUpdate::begin("/update.bin")`
- `OTAUpdate::download(url, "/update.bin")`
- `OTAUpdate::verify()`
- `OTAUpdate::update("/update.bin")`

The bridge firmware's `Arduino_UNOWIFIR4_OTA` class inherits from `Arduino_ESP32_OTA` and sets the Renesas sketch magic number:

```c
#define ARDUINO_RA4M1_OTA_MAGIC 0x23411002
```

When downloading to a file path, the ESP32-S3 bridge:

1. initializes SPIFFS and opens the requested path;
2. delegates the HTTP download and OTA parsing to `Arduino_ESP32_OTA`;
3. writes decoded bytes to the SPIFFS file via `write_byte_to_flash`;
4. verifies the container CRC and magic;
5. flashes the decoded file into the RA4M1 using `BOSSA.program(file_path, Serial, GPIO_BOOT, GPIO_RST)`.

This means the `.ota` is not an MCUboot image. In this path I found no signing step and no MCUboot signature check. The acceptance checks visible in source are the OTA magic number and CRC32, followed by BOSSA programming of the decompressed binary.

## Dependencies

The canonical scripts require:

- Python 3.
- Python package `crccheck` for `bin2ota.py`.
- The LZSS shared library loaded by `lzss.py`.

Install the Python package:

```bash
python3 -m pip install crccheck
```

`lzss.py` expects to run from the `extras/tools` directory because it loads:

```python
LZSS_SO_FILE = f"./lzss.{LZSS_SO_EXT}"
```

The upstream directory currently contains both `lzss.so` and `lzss.dylib`, so Linux and macOS use does not require building `lzss.c`. I did not find an upstream Makefile or documented native Windows build command.

## Caveats

- Board selector must be exactly `UNOR4WIFI`; that is what selects magic `0x23411002`.
- The bridge firmware checks the magic against `ARDUINO_RA4M1_OTA_MAGIC`. A container built with `ESP32`, `NANO_ESP32`, or another board's magic should fail with the OTA header magic-number error.
- The payload must be LZSS-compressed before `bin2ota.py`. The bridge-side parser always feeds the payload through the LZSS decoder.
- The HTTP response must include `Content-Length`; `Arduino_ESP32_OTA::startDownload()` returns `HttpHeaderError` if no content length is present.
- `OTAUpdate` non-blocking download requires at least UNO R4 WiFi bridge firmware `0.5.0`, per the ArduinoCore-renesas `OTANonBlocking` example. The blocking `download()` path is used by Arduino's basic OTA example.
- Native Windows use of `lzss.py` is not documented upstream. Use WSL/Linux/macOS, or treat native Windows support as a separate validation task.

## Sources

- `arduino-libraries/ArduinoIoTCloud` at commit `dfebda524f3e6479bb6604c8e3df09c4953b8c96`
  - `extras/tools/README.md`: documents `lzss.py --encode` followed by `bin2ota.py`.
    https://github.com/arduino-libraries/ArduinoIoTCloud/blob/dfebda524f3e6479bb6604c8e3df09c4953b8c96/extras/tools/README.md
  - `extras/tools/bin2ota.py`: implements `UNOR4WIFI`, magic `0x23411002`, version bytes, length, and CRC32.
    https://github.com/arduino-libraries/ArduinoIoTCloud/blob/dfebda524f3e6479bb6604c8e3df09c4953b8c96/extras/tools/bin2ota.py
  - `extras/tools/lzss.py`: invokes the LZSS shared library.
    https://github.com/arduino-libraries/ArduinoIoTCloud/blob/dfebda524f3e6479bb6604c8e3df09c4953b8c96/extras/tools/lzss.py
  - `extras/tools/lzss.c`: LZSS implementation used by the tool.
    https://github.com/arduino-libraries/ArduinoIoTCloud/blob/dfebda524f3e6479bb6604c8e3df09c4953b8c96/extras/tools/lzss.c

- `arduino-libraries/Arduino_ESP32_OTA` at commit `a8ffaad9624101e908d814f9e5ef755135e0c086`
  - `README.md`: points users to `ArduinoIoTCloud/extras/tools/lzss.py` and `bin2ota.py`.
    https://github.com/arduino-libraries/Arduino_ESP32_OTA/blob/a8ffaad9624101e908d814f9e5ef755135e0c086/README.md
  - `src/decompress/utility.h`: defines the 20-byte `OtaHeader` and 8-byte `HeaderVersion`.
    https://github.com/arduino-libraries/Arduino_ESP32_OTA/blob/a8ffaad9624101e908d814f9e5ef755135e0c086/src/decompress/utility.h
  - `src/Arduino_ESP32_OTA.cpp`: implements download parsing, LZSS decompression, magic check, CRC verification, and `Content-Length` requirement.
    https://github.com/arduino-libraries/Arduino_ESP32_OTA/blob/a8ffaad9624101e908d814f9e5ef755135e0c086/src/Arduino_ESP32_OTA.cpp

- `arduino/uno-r4-wifi-usb-bridge` at commit `94d5bb2e8c2cb5492345bfb84d787fde65d1c183`
  - `UNOR4USBBridge/OTA.h`: defines `ARDUINO_RA4M1_OTA_MAGIC 0x23411002` and the UNO R4 WiFi OTA class.
    https://github.com/arduino/uno-r4-wifi-usb-bridge/blob/94d5bb2e8c2cb5492345bfb84d787fde65d1c183/UNOR4USBBridge/OTA.h
  - `UNOR4USBBridge/OTA.cpp`: writes decoded bytes to SPIFFS and flashes the RA4M1 with BOSSA.
    https://github.com/arduino/uno-r4-wifi-usb-bridge/blob/94d5bb2e8c2cb5492345bfb84d787fde65d1c183/UNOR4USBBridge/OTA.cpp
  - `UNOR4USBBridge/cmds_ota.h`: maps Renesas-side AT OTA commands to the bridge OTA implementation.
    https://github.com/arduino/uno-r4-wifi-usb-bridge/blob/94d5bb2e8c2cb5492345bfb84d787fde65d1c183/UNOR4USBBridge/cmds_ota.h

- `arduino/ArduinoCore-renesas` at commit `99f8ee40613b165de124471d9a2b15bf5e2057fb`
  - `libraries/OTAUpdate/src/OTAUpdate.cpp`: shows the Renesas library forwards OTA operations to modem AT commands.
    https://github.com/arduino/ArduinoCore-renesas/blob/99f8ee40613b165de124471d9a2b15bf5e2057fb/libraries/OTAUpdate/src/OTAUpdate.cpp
  - `libraries/OTAUpdate/examples/OTA/OTA.ino`: official UNO R4 WiFi sketch OTA example using `UNOR4WIFI_Animation.ota`.
    https://github.com/arduino/ArduinoCore-renesas/blob/99f8ee40613b165de124471d9a2b15bf5e2057fb/libraries/OTAUpdate/examples/OTA/OTA.ino
  - `libraries/OTAUpdate/examples/OTANonBlocking/OTANonBlocking.ino`: notes non-blocking download requires bridge firmware `0.5.0`.
    https://github.com/arduino/ArduinoCore-renesas/blob/99f8ee40613b165de124471d9a2b15bf5e2057fb/libraries/OTAUpdate/examples/OTANonBlocking/OTANonBlocking.ino

## What's still unknown

- I did not find upstream documentation for native Windows execution of `lzss.py`. The canonical docs and shipped shared objects cover Linux/macOS style use.
- I did not find an Arduino CLI build hook that automatically emits `.ota` for `arduino:renesas_uno:unor4wifi`; the evidence points to the standalone `ArduinoIoTCloud/extras/tools` scripts as the canonical packaging path.
- I did not verify a generated CARDUINO `.ota` on hardware in this research pass. The source-level recipe is definitive, but hardware acceptance should still be tested once a candidate file is generated.
