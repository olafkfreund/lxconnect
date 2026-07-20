"""Self-contained MCP-over-SSE client for lxconnect.

Opens its own pinned-TLS session to the phone (reusing the cert pinning from
main.py), performs the MCP `initialize` handshake, and correlates tool results by
JSON-RPC id so callers get a synchronous `call(name, args) -> result`. The SSE
read loop runs on a background thread; phone-pushed notifications are delivered to
the `on_notification` callback.

The transport is injectable (`connect` param) so it can be unit-tested against a
mock server without a real device or TLS.
"""
import json
import threading
import time
import queue
from urllib.request import Request
from urllib.parse import urljoin

import main  # reuse get_connection_details / _pinned_opener / _require_fingerprint


notification_params = main.notification_params


class MCPClient:
    def __init__(self, on_notification=None, on_status=None, connect=None):
        self.on_notification = on_notification  # fn(params: dict)
        self.on_status = on_status              # fn(msg: str)
        self._connect = connect or self._default_connect
        self._pending = {}                      # id -> queue.Queue
        self._lock = threading.Lock()
        self._next_id = 0
        self._endpoint = None
        self._url = None
        self._headers = None
        self._opener = None
        self._ready = threading.Event()
        self._stop = False

    # -- transport (default = the pinned TLS path from main.py) --
    def _default_connect(self):
        url, headers, fp = main.get_connection_details()
        main._require_fingerprint(fp)
        return url, headers, main._pinned_opener(fp)

    # -- lifecycle --
    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def stop(self):
        self._stop = True

    def _status(self, msg):
        if self.on_status:
            try:
                self.on_status(msg)
            except Exception:
                pass

    def _run(self):
        while not self._stop:
            try:
                self._url, self._headers, self._opener = self._connect()
                self._status(f"Connecting to {self._url} …")
                req = Request(self._url, headers=self._headers)
                with self._opener.open(req) as resp:
                    event = None
                    for raw in resp:
                        if self._stop:
                            break
                        line = raw.decode("utf-8").rstrip("\r\n")
                        if not line:
                            continue
                        if line.startswith("event:"):
                            event = line.split(":", 1)[1].strip()
                        elif line.startswith("data:"):
                            self._on_line(event, line.split(":", 1)[1].strip())
            except Exception as e:
                self._status(f"Disconnected: {e}")
            self._ready.clear()
            self._endpoint = None
            if not self._stop:
                time.sleep(3)

    def _on_line(self, event, data):
        if event == "endpoint":
            self._endpoint = data
            self._status("Session established")
            threading.Thread(target=self._handshake, daemon=True).start()
        elif event == "message":
            self._on_message(data)

    def _post(self, payload):
        post_url = urljoin(self._url, self._endpoint)
        req = Request(post_url, data=json.dumps(payload).encode("utf-8"),
                      headers=self._headers, method="POST")
        self._opener.open(req)  # 202 Accepted; the reply arrives on the SSE stream

    def _handshake(self):
        try:
            q = queue.Queue()
            with self._lock:
                self._pending[-1] = q
            self._post({"jsonrpc": "2.0", "id": -1, "method": "initialize",
                        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                                   "clientInfo": {"name": "lxconnect-gui", "version": "1.0"}}})
            q.get(timeout=10)
            self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
            self._ready.set()
            self._status("Ready")
        except Exception as e:
            self._status(f"Handshake failed: {e}")

    def _on_message(self, data):
        try:
            msg = json.loads(data)
        except Exception:
            return
        if msg.get("method") == "notifications/phone_notification":
            if self.on_notification:
                try:
                    self.on_notification(notification_params(msg))
                except Exception:
                    pass
            return
        rid = msg.get("id")
        if rid is not None:
            with self._lock:
                q = self._pending.pop(rid, None)
            if q:
                q.put(msg)

    # -- public API --
    def call(self, name, arguments=None, timeout=20):
        """Call a tool; return {"text", "images": [b64...], "isError"}.
        Raises RuntimeError on tool/transport error, TimeoutError on no reply."""
        if not self._ready.wait(timeout):
            raise RuntimeError("MCP client not connected/ready")
        q = queue.Queue()
        with self._lock:
            self._next_id += 1
            rid = self._next_id
            self._pending[rid] = q
        try:
            self._post({"jsonrpc": "2.0", "id": rid, "method": "tools/call",
                        "params": {"name": name, "arguments": arguments or {}}})
        except Exception as e:
            with self._lock:
                self._pending.pop(rid, None)
            raise RuntimeError(f"dispatch failed: {e}")
        try:
            msg = q.get(timeout=timeout)
        except queue.Empty:
            with self._lock:
                self._pending.pop(rid, None)
            raise TimeoutError(f"no response to '{name}' within {timeout}s")
        if "error" in msg:
            raise RuntimeError(msg["error"].get("message", "tool error"))
        result = msg.get("result", {})
        texts, images = [], []
        for c in result.get("content", []):
            if c.get("type") == "image":
                images.append(c.get("data", ""))
            else:
                texts.append(c.get("text", ""))
        return {"text": "\n".join(texts), "images": images,
                "isError": bool(result.get("isError", False))}
