import os
import sys
import json
import base64
import time
import argparse
import subprocess
from urllib.request import Request, urlopen
from urllib.parse import urljoin

URL = "http://localhost:8085/"
HEADERS = {
    "Authorization": "Bearer test-key-999",
    "Content-Type": "application/json"
}
ENDPOINT_FILE = "/tmp/lxconnect_endpoint"

def notify_desktop(title, body):
    try:
        subprocess.run(["notify-send", "-a", "lxconnect", title, body])
    except Exception as e:
        print(f"Failed to send desktop notification: {e}")

def listen_daemon():
    print(f"Starting lxconnect daemon. Connecting to {URL}...")
    while True:
        try:
            req = Request(URL, headers=HEADERS)
            with urlopen(req) as response:
                print("--- Connected to Android MCP Stream ---")
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
                                    params = msg.get("params", {})
                                    title = params.get("title", "Android Notification")
                                    text = params.get("text", "")
                                    pkg = params.get("packageName", "")
                                    key = params.get("key", "")
                                    print(f"🔔 Incoming Notification! Key: {key}")
                                    notify_desktop(f"📱 {title}", f"{text}\n\nApp: {pkg}")
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
        print("Error: Daemon is not running or hasn't established a session. Start it with 'lxconnect daemon'.")
        sys.exit(1)
    with open(ENDPOINT_FILE, "r") as f:
        return f.read().strip()

def call_tool(tool_name, arguments):
    endpoint = get_active_endpoint()
    post_url = urljoin(URL, endpoint)
    payload = {
        "jsonrpc": "2.0",
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": arguments
        },
        "id": int(time.time())
    }
    req = Request(post_url, data=json.dumps(payload).encode('utf-8'), headers=HEADERS, method="POST")
    try:
        urlopen(req) # Server returns 202 Accepted, actual result arrives in the daemon's SSE loop
        print(f"Command '{tool_name}' dispatched to Android device.")
    except Exception as e:
        print(f"Failed to dispatch command: {e}")

def main():
    parser = argparse.ArgumentParser(description="lxconnect - Android to Linux Desktop Bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("daemon", help="Run the background daemon to receive notifications")

    open_p = subparsers.add_parser("open", help="Open a deep link URI on the Android device")
    open_p.add_argument("uri", help="The URI to open (e.g. spotify:search:hello or geo:0,0)")

    send_p = subparsers.add_parser("send-file", help="Send a file to the Android device")
    send_p.add_argument("filepath", help="Path to local file")
    
    get_p = subparsers.add_parser("get-file", help="Retrieve a file from the Android device Download/lxconnect folder")
    get_p.add_argument("filename", help="Filename to retrieve")

    stop_p = subparsers.add_parser("stop-app", help="Kill background processes of an Android app")
    stop_p.add_argument("package", help="Package name (e.g. com.spotify.music)")

    subparsers.add_parser("mock-notification", help="Post a mock Snapchat notification on the Android device")

    reply_p = subparsers.add_parser("reply", help="Reply to a notification using its key")
    reply_p.add_argument("key", help="The notification key")
    reply_p.add_argument("text", help="The reply message")

    subparsers.add_parser("list-notifications", help="List all currently active Android notifications")

    subparsers.add_parser("status", help="Get Android device detailed system status")
    ring_p = subparsers.add_parser("ring", help="Ring the Android device at max volume to find it")
    ring_p.add_argument("action", nargs="?", default="start", choices=["start", "stop"], help="Action: start or stop")

    args = parser.parse_args()

    if args.command == "daemon":
        listen_daemon()
    elif args.command == "open":
        call_tool("open_deep_link", {"uri": args.uri})
    elif args.command == "send-file":
        if not os.path.exists(args.filepath):
            print(f"File not found: {args.filepath}")
            sys.exit(1)
        with open(args.filepath, "rb") as f:
            b64 = base64.b64encode(f.read()).decode('utf-8')
        call_tool("send_file", {"fileName": os.path.basename(args.filepath), "base64Data": b64})
    elif args.command == "get-file":
        call_tool("get_file", {"fileName": args.filename})
    elif args.command == "stop-app":
        call_tool("stop_app", {"packageName": args.package})
    elif args.command == "mock-notification":
        call_tool("debug_mock_notification", {})
    elif args.command == "reply":
        call_tool("reply_to_notification", {"notificationKey": args.key, "replyText": args.text})
    elif args.command == "list-notifications":
        call_tool("get_active_notifications", {})
    elif args.command == "status":
        call_tool("get_detailed_status", {})
    elif args.command == "ring":
        call_tool("ring_device", {"action": args.action})

if __name__ == "__main__":
    main()
