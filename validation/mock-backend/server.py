#!/usr/bin/env python3
"""
Mock SyncEngine backend for on-device validation (INTEGRATION.md §2.3.D).

Stdlib only (no deps to install on the test machine). Single port serves both
the NoteApi surface the app's RetrofitSyncAdapter talks to (/notes*) and an
admin control surface (/admin/*) used by instrumented tests / manual scenarios
to inject faults without redeploying the app.

Run:  python server.py [port]        (default port 8080)
Bridge to device:  adb reverse tcp:8080 tcp:8080
App's debug base URL then points at http://127.0.0.1:8080/

Modes (set via POST /admin/mode {"mode": "..."}):
  normal     - push/pull/delete succeed
  http500    - push and delete return HTTP 500 (scenario D16)
  malformed  - pull returns invalid JSON (scenario D17)
  down       - connection is abruptly reset, no response (scenario D18)
  slow       - response delayed by /admin/slow_delay seconds, then normal (D21)

Remote state for pull/conflict scenarios (§2.3.C) is controlled via
POST /admin/remote_queue with a JSON array of notes; GET /notes?since=N
returns queued notes with lastModified > N.
"""
import json
import socketserver
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
STATE = {
    "mode": "normal",
    "slow_delay": 5.0,
    "remote_queue": [],   # list of note dicts the next pull(s) will return
    "pushed": {},         # id -> note dict, everything ever pushed
    "deleted": [],        # ids ever sent to /notes/delete
    "push_log": [],       # raw push bodies, for proof/audit
    "delete_log": [],
}


def _reset():
    STATE["mode"] = "normal"
    STATE["slow_delay"] = 5.0
    STATE["remote_queue"] = []
    STATE["pushed"] = {}
    STATE["deleted"] = []
    STATE["push_log"] = []
    STATE["delete_log"] = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock-backend] %s - %s\n" % (self.address_string(), fmt % args))

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else None

    def _json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _abrupt_close(self):
        # No response at all -> client sees a connection reset / IOException,
        # which the RetrofitSyncAdapter must surface as NetworkResult.NetworkError.
        # SO_LINGER(0) forces a hard RST instead of a graceful FIN, so a
        # keep-alive-aware HTTP client (OkHttp, curl) can't just sit waiting
        # on the still-open connection for more bytes that will never come.
        try:
            import socket as _socket
            self.connection.setsockopt(
                _socket.SOL_SOCKET, _socket.SO_LINGER, struct.pack("ii", 1, 0)
            )
        except Exception:
            pass
        try:
            self.connection.close()
        except Exception:
            pass
        self.close_connection = True

    def do_POST(self):
        with LOCK:
            self._route_post()

    def do_GET(self):
        with LOCK:
            self._route_get()

    # ---- admin (always answers, ignores current fault mode) ----
    def _route_post(self):
        if self.path == "/admin/mode":
            body = self._body() or {}
            mode = body.get("mode", "normal")
            if mode not in ("normal", "http500", "malformed", "down", "slow"):
                return self._json(400, {"error": "unknown mode"})
            STATE["mode"] = mode
            return self._json(200, {"mode": mode})

        if self.path == "/admin/slow_delay":
            body = self._body() or {}
            STATE["slow_delay"] = float(body.get("seconds", 5.0))
            return self._json(200, {"slow_delay": STATE["slow_delay"]})

        if self.path == "/admin/remote_queue":
            body = self._body()
            STATE["remote_queue"] = body if isinstance(body, list) else []
            return self._json(200, {"queued": len(STATE["remote_queue"])})

        if self.path == "/admin/reset":
            _reset()
            return self._json(200, {"reset": True})

        if self.path == "/notes/push":
            return self._notes_push()

        if self.path == "/notes/delete":
            return self._notes_delete()

        self._json(404, {"error": "no such route"})

    def _route_get(self):
        if self.path == "/admin/state":
            return self._json(200, STATE)

        if self.path.startswith("/notes"):
            return self._notes_pull()

        self._json(404, {"error": "no such route"})

    # ---- NoteApi surface, fault-mode aware ----
    def _notes_push(self):
        mode = STATE["mode"]
        if mode == "down":
            return self._abrupt_close()
        if mode == "slow":
            time.sleep(STATE["slow_delay"])
        body = self._body() or []
        STATE["push_log"].append(body)
        if mode == "http500":
            return self._json(500, {"error": "injected failure"})
        for note in body:
            STATE["pushed"][note["id"]] = note
        return self._json(200, {})

    def _notes_delete(self):
        mode = STATE["mode"]
        if mode == "down":
            return self._abrupt_close()
        if mode == "slow":
            time.sleep(STATE["slow_delay"])
        ids = self._body() or []
        STATE["delete_log"].append(ids)
        if mode == "http500":
            return self._json(500, {"error": "injected failure"})
        for i in ids:
            STATE["pushed"].pop(i, None)
            STATE["deleted"].append(i)
        return self._json(200, {})

    def _notes_pull(self):
        mode = STATE["mode"]
        if mode == "down":
            return self._abrupt_close()
        if mode == "slow":
            time.sleep(STATE["slow_delay"])
        if mode == "malformed":
            body = b"{not valid json"
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)

        since = 0
        if "?" in self.path:
            qs = self.path.split("?", 1)[1]
            for pair in qs.split("&"):
                if pair.startswith("since="):
                    since = int(pair.split("=", 1)[1])
        result = [n for n in STATE["remote_queue"] if n.get("lastModified", 0) > since]
        return self._json(200, result)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"[mock-backend] listening on :{port} — run 'adb reverse tcp:{port} tcp:{port}' to reach it from a device")
    server.serve_forever()
