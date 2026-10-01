#!/usr/bin/env python3
"""Serve a directory of build/model artifacts over HTTPS for LAN device testing.

Generic developer tool: point QA devices at this machine instead of a published
release. Generate a pair once (IP SAN = this machine's LAN address), serve an
artifacts directory, and have the test build trust the certificate only for that
address (e.g. via a locally added network security config source set).

  openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 3650 \
    -nodes -subj "/CN=hearth-dev-lan" -addext "subjectAltName=IP:$(ipconfig getifaddr en0)"
  python3 scripts/speech/serve-model-artifacts.py ARTIFACT_DIR --port 8443 --cert cert.pem --key key.pem
"""
import argparse
import http.server
import ssl
from pathlib import Path

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("directory", type=Path)
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--cert", type=Path, required=True)
    ap.add_argument("--key", type=Path, required=True)
    args = ap.parse_args()

    from functools import partial
    handler = partial(http.server.SimpleHTTPRequestHandler, directory=str(args.directory.resolve()))
    server = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"serving {args.directory.resolve()} at https://0.0.0.0:{args.port}/ (Ctrl-C to stop)")
    server.serve_forever()
