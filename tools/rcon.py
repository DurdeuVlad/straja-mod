#!/usr/bin/env python3
"""Minimal RCON client for the Straja dev server.

Usage:
    python tools/rcon.py "straja help"
    python tools/rcon.py --host 127.0.0.1 --port 25575 --password straja-dev "cmd"

Reads server.properties from run/ by default; STRAJA_RCON_HOST / _PORT /
_PASSWORD environment variables override it. The password is never printed.

Exit codes: 0 ok · 2 usage · 3 auth failed · 4 timeout · 5 protocol
error · 6 connection refused/unreachable.
"""
import os
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_AUTH_FAILED = 3
EXIT_TIMEOUT = 4
EXIT_PROTOCOL = 5
EXIT_CONNECT = 6

MAX_PACKET_LEN = 262144
_REQUEST_ID = 2
_SENTINEL_ID = 0x7FFFFFFE  # terminator: response for this id ends the read


class RconError(Exception):
    exit_code = EXIT_PROTOCOL


class RconAuthError(RconError):
    exit_code = EXIT_AUTH_FAILED


class RconTimeout(RconError):
    exit_code = EXIT_TIMEOUT


class RconConnectError(RconError):
    exit_code = EXIT_CONNECT


class RconProtocolError(RconError):
    exit_code = EXIT_PROTOCOL


def _packet(req_id, pkt_type, payload):
    body = struct.pack("<ii", req_id, pkt_type) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            return data
        data += chunk
    return data


def _read_packet(sock):
    """Return (req_id, payload). Raises RconProtocolError on bad framing."""
    header = _recv_exact(sock, 4)
    if len(header) < 4:
        raise RconProtocolError("truncated packet header")
    (length,) = struct.unpack("<i", header)
    if length < 10 or length > MAX_PACKET_LEN:
        raise RconProtocolError(f"invalid packet length {length}")
    body = _recv_exact(sock, length)
    if len(body) < length:
        raise RconProtocolError(f"truncated body: {len(body)}/{length}")
    req_id, _ptype = struct.unpack("<ii", body[:8])
    return req_id, body[8:-2].decode("utf-8", "replace")


def rcon(host, port, password, command, timeout=15):
    """Execute one command; return the assembled response text."""
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except socket.timeout as exc:
        raise RconTimeout(f"connect timed out: {host}:{port}") from exc
    except OSError as exc:
        raise RconConnectError(f"connect failed: {host}:{port}: {exc}") from exc
    sock.settimeout(timeout)
    try:
        sock.sendall(_packet(1, SERVERDATA_AUTH, password))
        req_id, _ = _read_packet(sock)
        if req_id == -1:
            raise RconAuthError("authentication rejected")
        sock.sendall(_packet(_REQUEST_ID, SERVERDATA_EXECCOMMAND, command))
        # Wait for the command response before sending the terminator. Some
        # NeoForge/Minecraft RCON builds close a pipelined request when the
        # command performs synchronous SavedData writes; the sentinel is only
        # needed after the first response and still captures split payloads.
        chunks = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                rid, payload = _read_packet(sock)
            except socket.timeout:
                if chunks:
                    break
                raise RconTimeout(f"no response to {command!r}") from None
            if rid == _REQUEST_ID:
                chunks.append(payload or "")
                if len(chunks) == 1:
                    # An empty command still yields a response, so the
                    # sentinel id marks end-of-response after the real reply.
                    sock.sendall(_packet(_SENTINEL_ID, SERVERDATA_EXECCOMMAND, ""))
            elif rid == _SENTINEL_ID:
                break
        else:
            raise RconTimeout(f"response to {command!r} exceeded {timeout}s")
        return "".join(chunks)
    except socket.timeout as exc:
        raise RconTimeout(f"timed out: {command!r}") from exc
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
    host = os.environ.get("STRAJA_RCON_HOST", "127.0.0.1")
    port = int(os.environ.get("STRAJA_RCON_PORT", "25575"))
    password = os.environ.get("STRAJA_RCON_PASSWORD")
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
    if not command or not password:
        print(__doc__)
        sys.exit(EXIT_USAGE)
    try:
        print(rcon(host, port, password, command))
    except RconError as exc:
        # Never echo credentials in error output.
        print(f"rcon: {exc}", file=sys.stderr)
        sys.exit(exc.exit_code)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
