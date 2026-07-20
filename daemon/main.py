import os
import sys
import json
import base64
import time
import argparse
import subprocess
import socket
import secrets
import tempfile
import threading
import hmac
import ssl
import hashlib
import http.client
import itertools
import notifier
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.request import Request, urlopen, HTTPSHandler, build_opener
from urllib.parse import urljoin

CONFIG_FILE = os.path.expanduser("~/.config/lxconnect/config.json")
ENDPOINT_FILE = "/tmp/lxconnect_endpoint"
expected_secret = None
pairing_result = None

def get_connection_details():
    # No usable defaults: an unpaired daemon fails closed in _require_fingerprint()
    # before url/token are ever used, so there's nothing sensible to guess here.
    url = None
    token = None
    fingerprint = None
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                config = json.load(f)
                ip = config.get("ip")
                port = config.get("port", 8080)
                secret = config.get("secret")
                fingerprint = config.get("certFingerprint")
                if ip:
                    url = f"https://{ip}:{port}/"
                if secret:
                    token = secret
        except Exception:
            pass
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Host": "localhost"
    }
    return url, headers, fingerprint

class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPSConnection that accepts the self-signed server cert only if its
    SHA-256 DER fingerprint matches the pin captured at pairing time."""
    pinned_fingerprint = None

    def connect(self):
        sock = socket.create_connection((self.host, self.port), self.timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.sock = ctx.wrap_socket(sock, server_hostname=self.host)
        der_cert = self.sock.getpeercert(binary_form=True)
        actual = hashlib.sha256(der_cert).hexdigest()
        if actual != self.pinned_fingerprint:
            self.sock.close()
            raise ssl.SSLError(
                f"Certificate pin mismatch (expected {self.pinned_fingerprint}, got {actual}). "
                "Refusing to connect - re-pair the device if it was legitimately reconfigured."
            )

def _pinned_opener(fingerprint):
    """Build a urllib opener that only completes TLS handshakes to the pinned cert."""
    conn_cls = type("_PinnedHTTPSConnectionBound", (_PinnedHTTPSConnection,), {"pinned_fingerprint": fingerprint})

    class _Handler(HTTPSHandler):
        def https_open(self, req):
            return self.do_open(conn_cls, req)

    return build_opener(_Handler())

def _require_fingerprint(fingerprint):
    if not fingerprint:
        raise RuntimeError(
            "No pinned certificate found for this device. Re-pair with 'lxconnect pair' to continue."
        )

def notification_params(msg):
    """The phone's notification fields out of an SSE message.

    The server sends them as MCP notification metadata, which serializes to the
    reserved `_meta` member of params rather than to params itself. Reading
    params directly yielded empty fields for every notification. Falling back to
    params keeps this working if that ever changes.
    """
    params = msg.get("params") or {}
    meta = params.get("_meta")
    return meta if isinstance(meta, dict) else params

def notify_desktop(title, body):
    notifier.simple(title, body)

_rich_notifier = None

def get_notifier():
    """The D-Bus notifier, started on first use."""
    global _rich_notifier
    if _rich_notifier is None:
        _rich_notifier = notifier.Notifier(call_tool, fetch_image)
        if not _rich_notifier.start():
            print("Notification server did not respond; using plain notifications.")
    return _rich_notifier

def notify_rich(notif):
    """Post a phone notification to the desktop. Runs off-thread: fetching an
    avatar round-trips to the phone and must not stall the SSE loop."""
    def worker():
        try:
            get_notifier().notify(notif)
        except Exception as e:
            print(f"Rich notification failed ({e}), falling back to plain.")
            notify_desktop(notif.get("title", "Android"), notif.get("text", ""))
    threading.Thread(target=worker, daemon=True).start()

def listen_daemon():
    print("Starting lxconnect daemon...")
    while True:
        try:
            # Re-read config each reconnect so re-pairing is picked up without a restart.
            url, headers, fingerprint = get_connection_details()
            _require_fingerprint(fingerprint)
            opener = _pinned_opener(fingerprint)
            req = Request(url, headers=headers)
            with opener.open(req) as response:
                print(f"--- Connected to Android MCP Stream ({url}) ---")
                notify_desktop("lxconnect", "Connected to Android device successfully!")
                current_event = None
                for line in response:
                    line = line.decode('utf-8').strip()
                    if not line: continue
                    if line.startswith("event:"):
                        current_event = line.split(":", 1)[1].strip()
                    elif line.startswith("data:"):
                        data_val = line.split(":", 1)[1].strip()
                        if current_event == "endpoint":
                            endpoint = data_val
                            # Cache the session endpoint so CLI commands can route POSTs
                            with open(ENDPOINT_FILE, "w") as f:
                                f.write(endpoint)
                            print(f"Session established. Endpoint: {endpoint}")
                        elif current_event == "message":
                            try:
                                msg = json.loads(data_val)
                                if msg.get("method") == "notifications/phone_notification":
                                    params = notification_params(msg)
                                    title = params.get("title", "Android Notification")
                                    text = params.get("text", "")
                                    pkg = params.get("packageName", "")
                                    key = params.get("key", "")
                                    print(f"🔔 {params.get('appLabel') or pkg}: {title}")
                                    notify_rich(params)
                                    _run_automations({"package": pkg, "title": title, "text": text, "key": key})
                                elif msg.get("method") == "notifications/phone_notification_removed":
                                    # Dismissed on the phone: retract the desktop popup too.
                                    removed_key = notification_params(msg).get("key", "")
                                    print(f"✕ dismissed on phone: {removed_key}")
                                    get_notifier().close(removed_key)
                                elif _resolve_pending(msg):
                                    pass # Delivered to the call_tool_await() caller
                                elif "result" in msg:
                                    # It's an async response to a CLI tool execution
                                    content = msg["result"].get("content", [])
                                    is_error = msg.get("result", {}).get("isError", False)
                                    for item in content:
                                        text_val = item.get("text", "")
                                        print(f"[{'ERROR' if is_error else 'SUCCESS'}]: {text_val}")
                                        if is_error:
                                            notify_desktop("lxconnect Error", text_val)
                            except Exception as e:
                                pass # Ignore malformed json
        except Exception as e:
            print(f"Connection lost or failed: {e}. Retrying in 5 seconds...")
            if os.path.exists(ENDPOINT_FILE):
                os.remove(ENDPOINT_FILE)
            time.sleep(5)

def get_active_endpoint():
    if not os.path.exists(ENDPOINT_FILE):
        return None
    with open(ENDPOINT_FILE, "r") as f:
        return f.read().strip()

_request_id = itertools.count(int(time.time()) * 1000)
_pending = {}
_pending_lock = threading.Lock()

def call_tool(tool_name, arguments, request_id=None):
    endpoint = get_active_endpoint()
    if not endpoint:
        raise RuntimeError("Daemon is not running or hasn't established a session. Start it with 'lxconnect daemon'.")
    url, headers, fingerprint = get_connection_details()
    _require_fingerprint(fingerprint)
    post_url = urljoin(url, endpoint)
    payload = {
        "jsonrpc": "2.0",
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": arguments
        },
        # Monotonic: a plain timestamp collided for calls in the same second.
        "id": request_id if request_id is not None else next(_request_id)
    }
    req = Request(post_url, data=json.dumps(payload).encode('utf-8'), headers=headers, method="POST")
    _pinned_opener(fingerprint).open(req) # Server returns 202 Accepted, actual result arrives in the daemon's SSE loop
    print(f"Command '{tool_name}' dispatched to Android device.")

def call_tool_await(tool_name, arguments, timeout=10):
    """call_tool, but blocks for the result the SSE loop routes back by id.
    Only usable from inside a running daemon; returns None on timeout."""
    request_id = next(_request_id)
    slot = {"event": threading.Event(), "result": None}
    with _pending_lock:
        _pending[request_id] = slot
    try:
        call_tool(tool_name, arguments, request_id=request_id)
        return slot["result"] if slot["event"].wait(timeout) else None
    finally:
        with _pending_lock:
            _pending.pop(request_id, None)

def _resolve_pending(msg):
    """True if this SSE result belonged to a call_tool_await() caller."""
    with _pending_lock:
        slot = _pending.get(msg.get("id"))
    if not slot:
        return False
    slot["result"] = msg.get("result")
    slot["event"].set()
    return True

def fetch_image(tool_name, arguments):
    """Blocking image fetch used by the notifier for icons and pictures."""
    result = call_tool_await(tool_name, arguments)
    if not result or result.get("isError"):
        return None
    for item in result.get("content", []):
        if item.get("type") == "image" and item.get("data"):
            try:
                return base64.b64decode(item["data"])
            except Exception:
                return None
    return None

class PairingHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # Suppress HTTP logs to keep terminal output clean

    def do_POST(self):
        global pairing_result
        if self.path == "/pair":
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode('utf-8'))
                received_secret = data.get("secret")
                device_name = data.get("deviceName", "Android Device")
                cert_fingerprint = data.get("certFingerprint")
                if received_secret is not None and hmac.compare_digest(received_secret, expected_secret):
                    # Trust the real TCP peer address, never the client-supplied "ip".
                    client_ip = self.client_address[0]
                    pairing_result = {
                        "ip": client_ip,
                        "port": 8080,
                        "secret": received_secret,
                        "deviceName": device_name,
                        "certFingerprint": cert_fingerprint
                    }
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"success"}')
                    return
            except Exception:
                pass
        self.send_response(400)
        self.end_headers()

def _generate_pair_cert():
    # Ephemeral self-signed cert for the one-shot HTTPS pairing server. The phone
    # pins its fingerprint (carried in the QR), so the private key never leaves this
    # machine and the shared secret is never sent in cleartext.
    d = tempfile.mkdtemp(prefix="lxconnect-pair-")
    cert_file = os.path.join(d, "cert.pem")
    key_file = os.path.join(d, "key.pem")
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", key_file, "-out", cert_file, "-days", "1",
         "-subj", "/CN=lxconnect-pair"],
        check=True, capture_output=True,
    )
    der = subprocess.run(
        ["openssl", "x509", "-in", cert_file, "-outform", "DER"],
        check=True, capture_output=True,
    ).stdout
    return cert_file, key_file, hashlib.sha256(der).hexdigest()

def run_pairing_server(secret, cert_file, key_file, port=8086):
    global expected_secret, pairing_result
    expected_secret = secret
    pairing_result = None

    server = HTTPServer(('0.0.0.0', port), PairingHandler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert_file, key_file)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)

    import select
    start_time = time.time()
    while pairing_result is None:
        if time.time() - start_time > 60:
            server.server_close()
            return None
        r, w, e = select.select([server.socket], [], [], 1.0)
        if r:
            server.handle_request()
            
    server.server_close()
    return pairing_result

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.254.254.254', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

def do_pair():
    secret = secrets.token_hex(16)
    port = 8086
    local_ip = get_local_ip()
    try:
        cert_file, key_file, pair_fp = _generate_pair_cert()
    except Exception as e:
        print(f"Failed to generate pairing certificate (is openssl installed?): {e}")
        sys.exit(1)
    pair_url = f"lxconnect://pair?ip={local_ip}&port={port}&secret={secret}&pairFp={pair_fp}"
    
    print("=============================================================")
    print("                      lxconnect Pairing                      ")
    print("=============================================================")
    print(f"Local IP: {local_ip}")
    print(f"Pairing URL: {pair_url}")
    print("Scan the QR code below or open the URL in your browser/device:")
    print("")
    
    try:
        subprocess.run(["qrencode", "-t", "ansiutf8", pair_url])
    except Exception:
        print("Install 'qrencode' package to view the QR code directly in the terminal.")
        print(f"Alternatively, open this link manually: {pair_url}")
        
    print("")
    print("Waiting for connection from mobile device (60s timeout)...")
    
    result = run_pairing_server(secret, cert_file, key_file, port)
    if result:
        os.makedirs(os.path.dirname(CONFIG_FILE), mode=0o700, exist_ok=True)
        os.chmod(os.path.dirname(CONFIG_FILE), 0o700)
        # Config holds the bearer token — write it 0600 so other local users can't read it.
        fd = os.open(CONFIG_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(result, f, indent=4)
        print("=============================================================")
        print("                   Pairing Successful!                       ")
        print("=============================================================")
        print(f"Device Name: {result['deviceName']}")
        print(f"Device IP:   {result['ip']}:{result['port']}")
        print("Configuration saved to ~/.config/lxconnect/config.json")
    else:
        print("Pairing failed or timed out.")

def _run_automations(notif):
    try:
        import automations
        for name, action in automations.process(notif, automations.load_rules(), call_tool, notify_desktop):
            print(f"⚙ Automation '{name}' → {action}")
    except Exception as e:
        print(f"Automation error: {e}")


def do_rules():
    import automations
    rules = automations.load_rules()
    if not rules:
        print(f"No automation rules. Create {automations.RULES_FILE} (see the module docstring for the format).")
        return
    for r in rules:
        print(f"• {r.get('name', '(unnamed)')}: match={r.get('match', {})} → {len(r.get('actions', []))} action(s)")


def do_triage():
    from mcp_client import MCPClient
    from collections import defaultdict
    client = MCPClient()
    client.start()
    try:
        r = client.call("list_notifications", {}, timeout=20)
    except Exception as e:
        print(f"Failed to fetch notifications: {e}")
        sys.exit(1)
    text = r.get("text", "")
    if not text or text.startswith("No active"):
        print("No active notifications.")
        return
    groups = defaultdict(list)
    for block in text.split("\n\n"):
        app = title = ""
        for line in block.splitlines():
            if line.startswith("App: "):
                app = line[5:].strip()
            elif line.startswith("Title: "):
                title = line[7:].strip()
        if app:
            groups[app].append(title)
    total = sum(len(v) for v in groups.values())
    print(f"{total} notifications across {len(groups)} apps:")
    for app, titles in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        shown = ", ".join(t for t in titles if t)[:80]
        print(f"  {app}: {len(titles)}" + (f" — {shown}" if shown else ""))


def send_file(args):
    if not os.path.exists(args.filepath):
        raise RuntimeError(f"File not found: {args.filepath}")
    with open(args.filepath, "rb") as f:
        b64 = base64.b64encode(f.read()).decode('utf-8')
    call_tool("send_file", {"fileName": os.path.basename(args.filepath), "base64Data": b64})

def main():
    parser = argparse.ArgumentParser(description="lxconnect - Android to Linux Desktop Bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)

    def cmd(name, help, func, *arguments):
        p = subparsers.add_parser(name, help=help)
        for arg_args, arg_kwargs in arguments:
            p.add_argument(*arg_args, **arg_kwargs)
        p.set_defaults(func=func)

    cmd("daemon", "Run the background daemon to receive notifications", lambda a: listen_daemon())
    cmd("pair", "Generate a QR code to pair with the Android phone", lambda a: do_pair())
    cmd("open", "Open a deep link URI on the Android device",
        lambda a: call_tool("open_deep_link", {"uri": a.uri}),
        (("uri",), {"help": "The URI to open (e.g. spotify:search:hello or geo:0,0)"}))
    cmd("send-file", "Send a file to the Android device", send_file,
        (("filepath",), {"help": "Path to local file"}))
    cmd("get-file", "Retrieve a file from the Android device Download/lxconnect folder",
        lambda a: call_tool("get_file", {"fileName": a.filename}),
        (("filename",), {"help": "Filename to retrieve"}))
    cmd("stop-app", "Kill background processes of an Android app",
        lambda a: call_tool("stop_app", {"packageName": a.package}),
        (("package",), {"help": "Package name (e.g. com.spotify.music)"}))
    cmd("reply", "Reply to a notification using its key",
        lambda a: call_tool("reply_to_notification", {"notificationKey": a.key, "replyText": a.text}),
        (("key",), {"help": "The notification key"}),
        (("text",), {"help": "The reply message"}))
    cmd("list-notifications", "List all currently active Android notifications",
        lambda a: call_tool("list_notifications", {}))
    cmd("rules", "List configured automation rules", lambda a: do_rules())
    cmd("triage", "Summarize active notifications grouped by app", lambda a: do_triage())
    cmd("list-apps", "List all installed apps on the Android device",
        lambda a: call_tool("list_installed_apps", {}))
    cmd("search-contacts", "Search contacts on the Android device",
        lambda a: call_tool("search_contacts", {"query": a.query}),
        (("query",), {"help": "Name to search for"}))
    cmd("status", "Get Android device detailed system status",
        lambda a: call_tool("get_detailed_status", {}))
    cmd("ring", "Ring the Android device at max volume to find it",
        lambda a: call_tool("ring_device", {"action": a.action}),
        (("action",), {"nargs": "?", "default": "start", "choices": ["start", "stop"], "help": "Action: start or stop"}))

    args = parser.parse_args()
    try:
        args.func(args)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
