import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib, Gio
import os
import sys
import json
import time
import threading
from urllib.request import Request, urlopen
from urllib.parse import urljoin

URL = "http://localhost:8085/"
HEADERS = {
    "Authorization": "Bearer test-key-999",
    "Content-Type": "application/json"
}
ENDPOINT_FILE = "/tmp/lxconnect_endpoint"

def get_active_endpoint():
    if not os.path.exists(ENDPOINT_FILE):
        return None
    with open(ENDPOINT_FILE, "r") as f:
        return f.read().strip()

def call_tool(tool_name, arguments):
    endpoint = get_active_endpoint()
    if not endpoint:
        raise Exception("Daemon is not running! Run 'lxconnect daemon' first.")
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
    urlopen(req)

def on_activate(app):
    print("GTK Activate fired! Creating window...")
    win = Gtk.ApplicationWindow(application=app, title="lxconnect Android Bridge")
    win.set_default_size(400, 250)

    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=15)
    box.set_margin_top(24)
    box.set_margin_bottom(24)
    box.set_margin_start(24)
    box.set_margin_end(24)
    win.set_child(box)

    # Header
    header = Gtk.Label(label="<span size='large' weight='bold'>Android Integration</span>", use_markup=True)
    box.append(header)

    # Status Label
    status_lbl = Gtk.Label(label="Checking connection...")
    box.append(status_lbl)

    if get_active_endpoint():
        status_lbl.set_markup("<span foreground='green'>✅ Connected to Waydroid</span>")
    else:
        status_lbl.set_markup("<span foreground='red'>❌ Daemon not running!</span>")

    box.append(Gtk.Separator())

    def run_in_background(tool, args):
        def worker():
            try:
                call_tool(tool, args)
                GLib.idle_add(status_lbl.set_markup, f"<span foreground='blue'>⚡ Sent: {tool}</span>")
            except Exception as e:
                GLib.idle_add(status_lbl.set_markup, f"<span foreground='red'>Error: {str(e)}</span>")
        threading.Thread(target=worker, daemon=True).start()

    # Deep Link Form
    link_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
    link_entry = Gtk.Entry()
    link_entry.set_placeholder_text("e.g., spotify:search:linux")
    link_entry.set_hexpand(True)
    
    link_btn = Gtk.Button(label="Open Deep Link")
    link_btn.add_css_class("suggested-action")
    link_btn.connect("clicked", lambda b: run_in_background("open_deep_link", {"uri": link_entry.get_text()}) if link_entry.get_text() else None)
    
    link_box.append(link_entry)
    link_box.append(link_btn)
    box.append(link_box)

    # Ring Buttons
    ring_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
    
    ring_btn = Gtk.Button(label="🔔 Ring Phone (Start)")
    ring_btn.set_hexpand(True)
    ring_btn.connect("clicked", lambda b: run_in_background("ring_device", {"action": "start"}))
    
    stop_ring_btn = Gtk.Button(label="🛑 Stop Ringing")
    stop_ring_btn.set_hexpand(True)
    stop_ring_btn.connect("clicked", lambda b: run_in_background("ring_device", {"action": "stop"}))
    
    ring_box.append(ring_btn)
    ring_box.append(stop_ring_btn)
    box.append(ring_box)

    # Status Button
    status_btn = Gtk.Button(label="📋 Fetch Android Status (Check Daemon Log)")
    status_btn.connect("clicked", lambda b: run_in_background("get_detailed_status", {}))
    box.append(status_btn)

    print("Presenting window...")
    win.set_visible(True)
    win.present()

app = Gtk.Application(application_id=None, flags=Gio.ApplicationFlags.NON_UNIQUE)
app.connect('activate', on_activate)
print("Starting GTK application loop...")
app.run(None)
