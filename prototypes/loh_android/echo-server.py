#!/usr/bin/env python3
"""
echo-server.py — minimal HTTP echo server for the Task 54 LOH bench test.

Run on a laptop joined to the LOH hotspot (after the Android prototype starts
the LOH and prints the SSID/passphrase). Prints its own LAN IP at startup —
that's the IP to put in MainActivity.kt's LAPTOP_IP constant.

Listens on 0.0.0.0:8080. POST /echo prints the body (and any headers worth
looking at) to stdout, replies 200 OK with the byte count.

Stop with Ctrl-C.
"""

from http.server import BaseHTTPRequestHandler, HTTPServer
import socket
import sys


class EchoHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""

        print()
        print(f"=== POST {self.path} from {self.client_address[0]} ===")
        # Print a few interesting headers
        for h in ("User-Agent", "Authorization", "Content-Type", "Host"):
            v = self.headers.get(h)
            if v:
                print(f"  {h}: {v}")
        print(f"  Content-Length: {length}")
        if length:
            preview = body[:200]
            try:
                printable = preview.decode("utf-8", errors="replace")
                print(f"  Body (first 200 B): {printable}")
            except Exception:
                print(f"  Body (raw, first 200 B): {preview!r}")
        sys.stdout.flush()

        msg = f"OK {length}".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(msg)))
        self.end_headers()
        self.wfile.write(msg)

    def log_message(self, format, *args):
        # Suppress the default access-log noise; we already print our own.
        pass


def get_local_ips():
    """Return all non-loopback IPv4 addresses bound to this host."""
    ips = []
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None, socket.AF_INET):
            ip = info[4][0]
            if ip != "127.0.0.1" and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips


def main():
    port = 8080
    print(f"echo-server.py — listening on 0.0.0.0:{port}")
    print()
    print("Local IPs detected (use whichever is on the LOH subnet for LAPTOP_IP):")
    for ip in get_local_ips():
        print(f"  {ip}")
    print()
    print("Waiting for POSTs… (Ctrl-C to stop)")
    sys.stdout.flush()

    server = HTTPServer(("0.0.0.0", port), EchoHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")


if __name__ == "__main__":
    main()
