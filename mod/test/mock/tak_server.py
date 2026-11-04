#!/usr/bin/env python3
"""Mock TAK Server HTTPS API with mutual TLS: the two endpoints TAK Lite uses.

  /Marti/sync/search?keywords=missionpackage   lists package.zip and mappack.zip
  /Marti/sync/content?hash=...                 serves whichever one matches

Usage: tak_server.py WORKDIR PORT   (certificates in WORKDIR/certs, from make_certs.sh)
"""
import hashlib
import http.server
import json
import os
import ssl
import sys
import urllib.parse

work, port = sys.argv[1], int(sys.argv[2])


def load(name):
    with open(os.path.join(work, name), "rb") as f:
        return f.read()


packages = {}
for name, title in (("package.zip", "TestArea"), ("mappack.zip", "TestMapPack")):
    data = load(name)
    packages[hashlib.sha256(data).hexdigest()] = (title, data)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        cert = self.connection.getpeercert()
        cn = dict(x[0] for x in cert["subject"])["commonName"] if cert else None
        print("GET", self.path, "client cert CN:", cn, flush=True)
        if url.path == "/Marti/sync/search":
            results = [{"UID": "uid-" + h[:8], "Name": title, "Hash": h, "Size": len(data),
                        "SubmissionUser": "test-user", "SubmissionDateTime": "2026-01-01T00:00:00.000Z",
                        "Keywords": ["missionpackage"], "MIMEType": "application/zip"}
                       for h, (title, data) in packages.items()]
            body = json.dumps({"resultCount": len(results), "results": results}).encode()
            self.reply(200, "application/json", body)
            return
        if url.path == "/Marti/sync/content" and query.get("hash", [""])[0] in packages:
            self.reply(200, "application/zip", packages[query["hash"][0]][1])
            return
        self.send_response(404)
        self.end_headers()

    def reply(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
certs = os.path.join(work, "certs")
context.load_cert_chain(os.path.join(certs, "server.pem"), os.path.join(certs, "server.key"))
context.load_verify_locations(os.path.join(certs, "ca.pem"))
context.verify_mode = ssl.CERT_REQUIRED
server = http.server.HTTPServer(("127.0.0.1", port), Handler)
server.socket = context.wrap_socket(server.socket, server_side=True)
print("mock TAK server listening on", port, flush=True)
server.serve_forever()
