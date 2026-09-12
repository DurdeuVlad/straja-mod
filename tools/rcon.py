#!/usr/bin/env python3
"""Minimal RCON client for the Straja dev server.

Usage:
    python tools/rcon.py "straja help"
    python tools/rcon.py --host 127.0.0.1 --port 25575 --password straja-dev "cmd"

Reads server.properties from run/ by default for host/port/password.
"""
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def _packet(req_id, pkt_type, payload):
    body = struct.pack("<ii", req_id, pkt_type) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _read_packet(sock):
    header = _recv_exact(sock, 4)
    if not header:
        return None, None
    (length,) = struct.unpack("<i", header)
    body = _recv_exact(sock, length)
    req_id, pkt_type = struct.unpack("<ii", body[:8])
    return req_id, body[8:-2].decode("utf-8", "replace")


def _recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            return data
        data += chunk
    return data


def rcon(host, port, password, command, timeout=15):
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(timeout)
    try:
        sock.sendall(_packet(1, SERVERDATA_AUTH, password))
        req_id, _ = _read_packet(sock)
        if req_id == -1:
            raise RuntimeError("RCON auth failed")
        sock.sendall(_packet(2, SERVERDATA_EXECCOMMAND, command))
        # Read until we get the response for id 2; the server may send
        # unrelated packets first.
        response = ""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                rid, payload = _read_packet(sock)
            except socket.timeout:
                break
            if rid is None:
                break
            if rid == 2:
                response += payload or ""
                break
        return response
    finally:
        sock.close()


def _props(path):
    props = {}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    props[k.strip()] = v.strip()
    except OSError:
        pass
    return props


def main():
    args = sys.argv[1:]
    host, port, password = "127.0.0.1", 25575, None
    i = 0
    while i < len(args):
        if args[i] == "--host":
            host = args[i + 1]; i += 2
        elif args[i] == "--port":
            port = int(args[i + 1]); i += 2
        elif args[i] == "--password":
            password = args[i + 1]; i += 2
        else:
            break
        i = i
    command = " ".join(args[i:])
    if password is None:
        props = _props("run/server.properties")
        password = props.get("rcon.password", "")
        port = int(props.get("rcon.port", port))
    if not command:
        print(__doc__)
        sys.exit(2)
    print(rcon(host, port, password, command))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
