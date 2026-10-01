#!/usr/bin/env python3
"""Tiny threaded HTTP server for handing the built APK to the phone's browser.

The app runs inside a proot guest, so /sdcard and the SD card are not reachable
from here. Proot does share the network namespace with Android though, which means
127.0.0.1 inside the guest is the phone's own loopback -- so serving the file over
HTTP lets Chrome download it straight into /sdcard/Download, which no file manager
can reach (Android 11+ blocks Android/data/).

Threaded because the stock http.server is single-threaded: a browser opening a
range request plus a favicon probe would otherwise stall the transfer.
"""
import functools
import http.server
import os
import socketserver

PORT = int(os.environ.get("APK_PORT", "8000"))
ROOT = os.environ.get("APK_ROOT", os.path.dirname(os.path.abspath(__file__)))


class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # No caching: a rebuild must be picked up on the next reload, and the
        # filename is stable across builds so the browser would otherwise reuse
        # a stale copy.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    handler = functools.partial(Handler, directory=ROOT)
    with Server(("0.0.0.0", PORT), handler) as httpd:
        print("serving %s on 0.0.0.0:%d" % (ROOT, PORT), flush=True)
        httpd.serve_forever()