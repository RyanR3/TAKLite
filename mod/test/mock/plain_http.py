#!/usr/bin/env python3
"""Plain http server standing in for ATAK sending a package straight from its phone.

  /package.zip   the package
  /redirect      302 to /package.zip (TAK Lite must not follow it)

Usage: plain_http.py WORKDIR PORT
"""
import http.server
import os
import sys

work, port = sys.argv[1], int(sys.argv[2])
with open(os.path.join(work, "package.zip"), "rb") as f:
    package = f.read()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        print("HTTP GET", self.path, flush=True)
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "http://127.0.0.1:%d/package.zip" % port)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(package)))
        self.end_headers()
        self.wfile.write(package)

    def log_message(self, *args):
        pass


print("plain http server listening on", port, flush=True)
http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
