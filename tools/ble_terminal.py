#!/usr/bin/env python3
"""
EXPANDASQUIRT v4 BLE NUS terminal — Windows / cross-platform via bleak.

Acts as a thin BLE-as-serial terminal. Subscribes to the TX characteristic
(device->host notifications), prints whatever the device sends to stdout,
and forwards each stdin line + '\\n' to the RX characteristic. Same role as
"Serial Bluetooth Terminal" / "LightBlue" on phone, but runs from a PC
shell.

Usage:
    python tools/ble_terminal.py                       # scan by name
    python tools/ble_terminal.py --name EXPANDASQUIRT
    python tools/ble_terminal.py --address XX:XX:XX:XX:XX:XX

Requires: pip install bleak

Press Ctrl+C to quit.
"""

import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner


# Nordic UART Service UUIDs — must match config.h BLE_SERVICE_UUID etc.
NUS_TX_CHAR = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  # device -> host (notify)
NUS_RX_CHAR = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  # host -> device (write)


async def find_device(name: str, timeout: float):
    print(f"Scanning for {name!r} (timeout {timeout:.0f}s)...", file=sys.stderr)
    device = await BleakScanner.find_device_by_name(name, timeout=timeout)
    if device is None:
        print(f"Device {name!r} not found.", file=sys.stderr)
    return device


def on_notify(_sender, data: bytearray) -> None:
    # Device sends UTF-8 text; just pass through. Notifications often arrive
    # as 19-byte chunks (MTU - 1) so a "line" can be split across multiple
    # callbacks — that's fine for human-eyeball reading.
    sys.stdout.write(data.decode("utf-8", errors="replace"))
    sys.stdout.flush()


async def stdin_pump(client: BleakClient) -> None:
    """Forward each stdin line (with appended \\n) to the RX characteristic."""
    loop = asyncio.get_running_loop()
    while client.is_connected:
        try:
            line = await loop.run_in_executor(None, sys.stdin.readline)
        except (KeyboardInterrupt, EOFError):
            return
        if not line:
            return  # EOF
        line = line.rstrip("\r\n") + "\n"
        try:
            await client.write_gatt_char(NUS_RX_CHAR, line.encode("utf-8"),
                                         response=False)
        except Exception as e:
            print(f"\n[write failed: {e}]", file=sys.stderr)
            return


async def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__.strip().splitlines()[0],
    )
    ap.add_argument("--name", default="EXPANDASQUIRT",
                    help="device name to scan for (default: EXPANDASQUIRT)")
    ap.add_argument("--address",
                    help="BLE address (skips scan if given)")
    ap.add_argument("--scan-timeout", type=float, default=10.0,
                    help="scan timeout in seconds (default: 10)")
    args = ap.parse_args()

    if args.address:
        target = args.address
    else:
        target = await find_device(args.name, args.scan_timeout)
        if target is None:
            sys.exit(1)

    async with BleakClient(target) as client:
        await client.start_notify(NUS_TX_CHAR, on_notify)
        print("Connected. Type commands and press Enter. Ctrl+C to exit.\n",
              file=sys.stderr)
        try:
            await stdin_pump(client)
        except asyncio.CancelledError:
            pass
        finally:
            try:
                await client.stop_notify(NUS_TX_CHAR)
            except Exception:
                pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.stderr.write("\nDisconnected.\n")
