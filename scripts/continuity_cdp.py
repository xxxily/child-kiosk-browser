#!/usr/bin/env python3
"""Minimal WebView CDP client for the background-continuity PoC."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import time
import urllib.request
from urllib.parse import urlparse


DEFAULT_PACKAGE = "site.anzz.childkiosk.continuitypoc"
DEFAULT_PROCESS = f"{DEFAULT_PACKAGE}:webview"


def adb(serial: str, *args: str) -> str:
    command = ["adb", "-s", serial, *args]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return completed.stdout.strip()


def discover_target(
    serial: str,
    process: str,
    port: int,
    url_hint: str,
    timeout_seconds: float = 15.0,
) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    last_pages: list[dict] = []
    while time.monotonic() < deadline:
        try:
            pid_output = adb(serial, "shell", "pidof", process)
            if not pid_output:
                raise RuntimeError(f"Process not started: {process}")
            pid = pid_output.split()[0]
            adb(
                serial,
                "forward",
                f"tcp:{port}",
                f"localabstract:webview_devtools_remote_{pid}",
            )
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/json/list",
                timeout=2,
            ) as response:
                targets = json.load(response)
            last_pages = [target for target in targets if target.get("type") == "page"]
            matching = [target for target in last_pages if url_hint in target.get("url", "")]
            if matching:
                return matching[0]
        except Exception as error:  # Retry while the process, socket and page target are starting.
            last_error = error
        time.sleep(0.25)
    raise RuntimeError(
        f"No page target matched {url_hint!r}; pages={last_pages!r}; last_error={last_error!r}"
    )


class DevToolsWebSocket:
    def __init__(self, websocket_url: str, forwarded_port: int) -> None:
        parsed = urlparse(websocket_url)
        self.socket = socket.create_connection(("127.0.0.1", forwarded_port), timeout=5)
        self.socket.settimeout(5)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        path = parsed.path or "/"
        if parsed.query:
            path += f"?{parsed.query}"
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{forwarded_port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.socket.sendall(request.encode("ascii"))
        response = self._read_until(b"\r\n\r\n")
        if not response.startswith(b"HTTP/1.1 101"):
            raise RuntimeError(f"WebSocket handshake failed: {response!r}")
        expected = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        )
        if expected not in response:
            raise RuntimeError("WebSocket handshake accept key mismatch")
        self.next_id = 1

    def close(self) -> None:
        try:
            self._send_frame(b"", opcode=0x8)
        finally:
            self.socket.close()

    def call(self, method: str, params: dict | None = None) -> dict:
        request_id = self.next_id
        self.next_id += 1
        self._send_frame(
            json.dumps(
                {"id": request_id, "method": method, "params": params or {}},
                separators=(",", ":"),
            ).encode()
        )
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            message = json.loads(self._receive_message().decode())
            if message.get("id") == request_id:
                if "error" in message:
                    raise RuntimeError(f"CDP {method} failed: {message['error']}")
                return message.get("result", {})
        raise TimeoutError(f"Timed out waiting for {method}")

    def _read_until(self, marker: bytes) -> bytes:
        data = bytearray()
        while marker not in data:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise EOFError("Socket closed during handshake")
            data.extend(chunk)
        return bytes(data)

    def _read_exact(self, size: int) -> bytes:
        data = bytearray()
        while len(data) < size:
            chunk = self.socket.recv(size - len(data))
            if not chunk:
                raise EOFError("WebSocket closed")
            data.extend(chunk)
        return bytes(data)

    def _send_frame(self, payload: bytes, opcode: int = 0x1) -> None:
        mask = os.urandom(4)
        length = len(payload)
        header = bytearray([0x80 | opcode])
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", length))
        header.extend(mask)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.socket.sendall(header + masked)

    def _receive_message(self) -> bytes:
        fragments = bytearray()
        while True:
            first, second = self._read_exact(2)
            final = bool(first & 0x80)
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else b""
            payload = self._read_exact(length)
            if masked:
                payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 0x9:
                self._send_frame(payload, opcode=0xA)
                continue
            if opcode == 0x8:
                raise EOFError("DevTools WebSocket closed")
            fragments.extend(payload)
            if final:
                return bytes(fragments)


def snapshot(client: DevToolsWebSocket) -> dict | None:
    result = client.call(
        "Runtime.evaluate",
        {
            "expression": (
                "JSON.stringify(window.__continuityProbe "
                "? window.__continuityProbe.snapshot() : null)"
            ),
            "returnByValue": True,
        },
    )
    value = result.get("result", {}).get("value")
    return json.loads(value) if value else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=("list", "connect", "snapshot", "active", "edge", "evaluate"),
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument("--process", default=DEFAULT_PROCESS)
    parser.add_argument("--port", type=int, default=9222)
    parser.add_argument("--url-hint", default="continuity_probe.html")
    parser.add_argument("--expression", default="document.visibilityState")
    parser.add_argument("--edge-delay", type=float, default=0.5)
    parser.add_argument("--hold-seconds", type=float, default=120.0)
    args = parser.parse_args()

    command_started_wall_ms = int(time.time() * 1000)
    target = discover_target(args.serial, args.process, args.port, args.url_hint)
    if args.command == "list":
        print(json.dumps(target, ensure_ascii=False, indent=2))
        return

    client = DevToolsWebSocket(target["webSocketDebuggerUrl"], args.port)
    try:
        if args.command == "connect":
            print(json.dumps({"target": target, "connected": True}, ensure_ascii=False))
            time.sleep(args.hold_seconds)
            return

        before: dict | None = None
        result: dict | None = None
        if args.command == "snapshot":
            client.call("Runtime.enable")
            before = snapshot(client)
            result = before
        elif args.command == "active":
            result = client.call("Page.setWebLifecycleState", {"state": "active"})
        elif args.command == "edge":
            frozen_result = client.call("Page.setWebLifecycleState", {"state": "frozen"})
            time.sleep(args.edge_delay)
            active_result = client.call("Page.setWebLifecycleState", {"state": "active"})
            result = {"frozen": frozen_result, "active": active_result}
        elif args.command == "evaluate":
            client.call("Runtime.enable")
            result = client.call(
                "Runtime.evaluate",
                {"expression": args.expression, "returnByValue": True},
            )
        print(
            json.dumps(
                {
                    "command": args.command,
                    "commandStartedWallMs": command_started_wall_ms,
                    "commandFinishedWallMs": int(time.time() * 1000),
                    "target": target,
                    "before": before,
                    "after": result,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    finally:
        client.close()


if __name__ == "__main__":
    main()
