#!/usr/bin/env python3
"""
CARDUINO v4 CAN bus verification tool.

Listens on a CAN interface (default: CANable v2 slcan @ 500kbps on COM9)
and decodes Frame 1 (0x401) + Frame 2 (0x402) per the design doc layout.
Prints each frame as it arrives plus a periodic rate summary.

Usage:
    python tools/can_verify.py                 # default COM9 @ 500k
    python tools/can_verify.py --port COM6
    python tools/can_verify.py --bitrate 250000

Press Ctrl+C to stop. Requires `pip install python-can`.
"""

import argparse
import struct
import time
from collections import defaultdict

import can


FRAME1_ID = 0x401  # engine sensors: oilT, oilP, fuelP, preP
FRAME2_ID = 0x402  # post-SC temp + status


def decode_frame1(data: bytes) -> str:
    oil_t, oil_p, fuel_p, pre_sc = struct.unpack(">HHHH", data)
    return (
        f"oilT={oil_t/10:5.1f}F  oilP={oil_p/10:5.1f}psi  "
        f"fuelP={fuel_p/10:5.1f}psi  preP={pre_sc/10:5.1f}kPa"
    )


def decode_frame2(data: bytes) -> str:
    post_t = (data[0] << 8) | data[1]
    seq = data[4]
    health = data[5]
    flags = data[6]
    age = data[7]
    return (
        f"postT={post_t/10:5.1f}F  seq={seq:3d}  "
        f"health=0x{health:02X}  flags=0x{flags:02X}  age={age}"
    )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--port", default="COM9", help="serial port for slcan (default: COM9)")
    ap.add_argument("--bitrate", default=500000, type=int, help="CAN bitrate (default: 500000)")
    ap.add_argument("--summary-window", default=5.0, type=float,
                    help="seconds between rate summary lines")
    args = ap.parse_args()

    print(f"Connecting to {args.port} @ {args.bitrate // 1000} kbps via slcan...")
    bus = can.Bus(interface="slcan", channel=args.port, bitrate=args.bitrate)
    print("Listening. Ctrl+C to stop.\n")

    counts: dict[int, int] = defaultdict(int)
    last_summary = time.time()
    last_seq: int | None = None
    seq_anomalies = 0
    no_frame_warned = False

    try:
        while True:
            msg = bus.recv(timeout=1.0)
            if msg is None:
                # No frame this second; only complain once
                if not no_frame_warned and time.time() - last_summary > args.summary_window:
                    print("(no frames received in last second)")
                    no_frame_warned = True
                    last_summary = time.time()
                continue
            no_frame_warned = False

            counts[msg.arbitration_id] += 1
            id_hex = f"0x{msg.arbitration_id:03X}"
            data_hex = msg.data.hex(" ")

            if msg.arbitration_id == FRAME1_ID:
                if len(msg.data) != 8:
                    print(f"  {id_hex}  {data_hex}  !! malformed (DLC={len(msg.data)}, expected 8)")
                else:
                    print(f"  {id_hex}  {data_hex}  {decode_frame1(msg.data)}")
            elif msg.arbitration_id == FRAME2_ID:
                if len(msg.data) != 8:
                    print(f"  {id_hex}  {data_hex}  !! malformed (DLC={len(msg.data)}, expected 8)")
                else:
                    seq = msg.data[4]
                    if last_seq is not None:
                        expected = (last_seq + 1) & 0xFF
                        if seq != expected:
                            seq_anomalies += 1
                            print(f"  !! SEQ skip: expected {expected}, got {seq}")
                    last_seq = seq
                    print(f"  {id_hex}  {data_hex}  {decode_frame2(msg.data)}")
            else:
                print(f"  {id_hex}  {data_hex}  (unknown)")

            now = time.time()
            if now - last_summary >= args.summary_window:
                window = now - last_summary
                f1 = counts[FRAME1_ID]
                f2 = counts[FRAME2_ID]
                print(
                    f"  --- {window:.1f}s window: "
                    f"0x401={f1} ({f1/window:.1f}Hz)  "
                    f"0x402={f2} ({f2/window:.1f}Hz)  "
                    f"seq anomalies={seq_anomalies} ---"
                )
                counts.clear()
                last_summary = now

    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        bus.shutdown()


if __name__ == "__main__":
    main()
