#!/usr/bin/env python3
"""
Mock AquaSense v3 ESP32 HTTP server (stdlib only).
Usage:
    python -I tools/mock_esp.py --port 8765
    adb reverse tcp:8765 tcp:8765

Endpoints:
  GET  /readings
  POST /time?epoch=N
  GET  /log?since=S&max=M
  POST /cal/start
  GET  /cal
  POST /station?name=...
  POST /mock/button
  POST /mock/mode?m=clear|cloudy|unplugged|stale|v2
  POST /mock/reboot
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

MIN_EPOCH = 1767225600  # 2026-01-01T00:00:00Z


class MockEspState:
    def __init__(self):
        self.boot = 1
        self.boot_start = time.monotonic()
        self.mode = "clear"
        self.limit = 5.0
        self.cal_v = 4.200
        self.cal_epoch = None
        self.station = "AquaSense Station"
        self.anchors = {}  # boot -> (epoch, uptime_s)
        self.entries = []  # [seq, boot, uptime_s, value, kind]
        self.seq = 0

        # Calibration state machine
        self.cal_state = "idle"
        self.cal_reason = None
        self.cal_started_at = None
        self.cal_ready_at = None

        # Seed a few historical minute points on boot 1
        for m in range(1, 6):
            self.append_log(m * 60, 1.4, 0)

    def uptime_s(self) -> int:
        return int(time.monotonic() - self.boot_start)

    def append_log(self, up_s: int, value: float, kind: int):
        self.seq += 1
        self.entries.append([self.seq, self.boot, up_s, round(value, 3 if kind == 1 else 2), kind])

    def current_ntu_and_volts(self):
        if self.mode == "unplugged":
            return None, 0.050, None
        if self.mode == "stale":
            return 1.80, 4.110, 25000
        if self.mode == "cloudy":
            return 8.70, 3.650, 120
        return 1.60, 4.150, 120

    def tick_cal(self):
        now = time.monotonic()
        if self.cal_state == "settling" and self.cal_started_at is not None:
            elapsed = int(now - self.cal_started_at)
            if self.mode == "unplugged":
                self.cal_state = "rejected"
                self.cal_reason = "out_of_range"
            elif self.mode == "cloudy":
                if elapsed >= 3:
                    self.cal_state = "rejected"
                    self.cal_reason = "too_low"
            elif elapsed >= 5:
                self.cal_state = "ready"
                self.cal_reason = None
                self.cal_ready_at = now
        elif self.cal_state == "ready" and self.cal_ready_at is not None:
            if now - self.cal_ready_at > 60:
                self.cal_state = "expired"

    def seconds_stable(self) -> int:
        if self.cal_state == "settling" and self.cal_started_at is not None:
            return min(60, int(time.monotonic() - self.cal_started_at) * 12)
        if self.cal_state in ("ready", "done"):
            return 60
        return 0


STATE = MockEspState()


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 0:
            self.rfile.read(length)
        self._handle("POST")

    def _handle(self, method: str):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        STATE.tick_cal()

        if path == "/readings":
            ntu, volts, age_ms = STATE.current_ntu_and_volts()
            if STATE.mode == "v2":
                self._send_json(200, {
                    "turbidity": ntu,
                    "turbidity_v": volts,
                    "age_ms": age_ms,
                    "uptime_s": STATE.uptime_s(),
                })
                return
            self._send_json(200, {
                "turbidity": ntu,
                "turbidity_v": volts,
                "age_ms": age_ms,
                "uptime_s": STATE.uptime_s(),
                "limit": STATE.limit,
                "boot": STATE.boot,
                "cal_v": STATE.cal_v,
                "cal_epoch": STATE.cal_epoch,
                "station": STATE.station,
            })
            return

        if STATE.mode == "v2" and path in ("/time", "/log", "/cal", "/cal/start", "/station"):
            self._send_json(404, {"error": "not found"})
            return

        if path == "/time":
            epoch_str = qs.get("epoch", [""])[0]
            accepted = False
            if epoch_str.isdigit():
                epoch = int(epoch_str)
                if epoch >= MIN_EPOCH and STATE.boot not in STATE.anchors:
                    STATE.anchors[STATE.boot] = (epoch, STATE.uptime_s())
                    accepted = True
            self._send_json(200, {"accepted": accepted})
            return

        if path == "/log":
            since = int(qs.get("since", ["0"])[0] or 0)
            max_n = min(100, int(qs.get("max", ["100"])[0] or 100))
            filtered = [e for e in STATE.entries if e[0] > since][:max_n]
            anchors_list = [[b, ep, up] for b, (ep, up) in STATE.anchors.items()]
            self._send_json(200, {
                "boot": STATE.boot,
                "uptime_s": STATE.uptime_s(),
                "last_seq": STATE.seq,
                "oldest_seq": 1,
                "anchors": anchors_list,
                "entries": filtered,
            })
            return

        if path == "/cal/start" and method == "POST":
            STATE.cal_state = "settling"
            STATE.cal_reason = None
            STATE.cal_started_at = time.monotonic()
            STATE.cal_ready_at = None
            self._send_json(200, {"state": "settling"})
            return

        if path == "/cal":
            _, volts, _ = STATE.current_ntu_and_volts()
            self._send_json(200, {
                "state": STATE.cal_state,
                "reason": STATE.cal_reason,
                "volts": volts,
                "seconds_stable": STATE.seconds_stable(),
                "cal_v": STATE.cal_v,
            })
            return

        if path == "/station" and method == "POST":
            name = qs.get("name", [""])[0].strip()[:40].strip()
            if not name:
                self._send_json(400, {"ok": False})
                return
            STATE.station = name
            self._send_json(200, {"ok": True})
            return

        if path == "/mock/button" and method == "POST":
            if STATE.cal_state == "ready":
                STATE.cal_state = "done"
                STATE.cal_v = 4.180
                if STATE.boot in STATE.anchors:
                    ep, up0 = STATE.anchors[STATE.boot]
                    STATE.cal_epoch = ep + (STATE.uptime_s() - up0)
                STATE.append_log(STATE.uptime_s(), STATE.cal_v, 1)
            self._send_json(200, {"state": STATE.cal_state})
            return

        if path == "/mock/mode":
            m = qs.get("m", [""])[0]
            if m in ("clear", "cloudy", "unplugged", "stale", "v2"):
                STATE.mode = m
            self._send_json(200, {"mode": STATE.mode})
            return

        if path == "/mock/reboot" and method == "POST":
            STATE.boot += 1
            STATE.boot_start = time.monotonic()
            self._send_json(200, {"boot": STATE.boot})
            return

        self._send_json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        return


def main():
    parser = argparse.ArgumentParser(description="Mock AquaSense ESP32 server")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    srv = HTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Mock AquaSense ESP32 listening on http://127.0.0.1:{args.port}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
