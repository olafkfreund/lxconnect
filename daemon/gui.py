"""lxconnect GTK4 desktop client.

A full MCP client (its own pinned-TLS session via mcp_client.MCPClient) that both
exercises every phone feature as a self-test and provides interactive controls.
Requires a paired device (run `lxconnect pair` first).
"""
import base64
import threading

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Gtk, Gdk, GLib, Gio

from mcp_client import MCPClient


# Curated safe/read-only suite for "Run all feature tests" (no SMS spam, no app kills).
FEATURE_TESTS = [
    ("get_device_info", {}),
    ("get_detailed_status", {}),
    ("list_notifications", {}),
    ("get_media_status", {}),
    ("get_clipboard", {}),
    ("list_files", {}),
    ("search_contacts", {"query": "a"}),
    ("list_installed_apps", {}),
    ("read_screen", {}),
    ("get_sms_history", {"limit": 3}),
    ("set_clipboard", {"text": "lxconnect self-test"}),
    ("ring_device", {"action": "start"}),
    ("ring_device", {"action": "stop"}),
    ("take_picture", {}),
]


class LxConnect(Gtk.Application):
    def __init__(self):
        super().__init__(application_id="com.lxconnect.gui",
                         flags=Gio.ApplicationFlags.NON_UNIQUE)
        self.client = None

    def do_activate(self):
        self.win = Gtk.ApplicationWindow(application=self, title="lxconnect")
        self.win.set_default_size(720, 640)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        root.set_margin_top(10)
        root.set_margin_bottom(10)
        root.set_margin_start(10)
        root.set_margin_end(10)
        self.win.set_child(root)

        self.status_lbl = Gtk.Label(xalign=0)
        self.status_lbl.set_markup("<b>Connecting…</b>")
        root.append(self.status_lbl)

        notebook = Gtk.Notebook()
        notebook.set_vexpand(True)
        root.append(notebook)
        notebook.append_page(self._tests_tab(), Gtk.Label(label="Tests"))
        notebook.append_page(self._device_tab(), Gtk.Label(label="Device"))
        notebook.append_page(self._messaging_tab(), Gtk.Label(label="Messaging"))
        notebook.append_page(self._screen_tab(), Gtk.Label(label="Screen"))
        notebook.append_page(self._camera_tab(), Gtk.Label(label="Camera"))

        # Shared output log at the bottom.
        self.output = Gtk.TextView(editable=False, monospace=True, wrap_mode=Gtk.WrapMode.WORD_CHAR)
        sc = Gtk.ScrolledWindow()
        sc.set_min_content_height(150)
        sc.set_child(self.output)
        root.append(Gtk.Label(label="Output", xalign=0))
        root.append(sc)

        self.client = MCPClient(on_notification=self._on_notification,
                                on_status=self._on_status)
        self.client.start()
        self.win.present()

    # ---- helpers ----
    def _log(self, text):
        def append():
            buf = self.output.get_buffer()
            buf.insert(buf.get_end_iter(), text + "\n")
        GLib.idle_add(append)

    def _on_status(self, msg):
        GLib.idle_add(lambda: self.status_lbl.set_markup(f"<b>{GLib.markup_escape_text(msg)}</b>"))

    def _on_notification(self, params):
        title = params.get("title", "Notification")
        text = params.get("text", "")
        self._log(f"🔔 {title}: {text}")

    def _run(self, tool, args, label=None):
        """Call a tool on a worker thread and log the result."""
        label = label or tool
        self._log(f"→ {label} …")

        def worker():
            try:
                r = self.client.call(tool, args)
                tag = "ERR" if r["isError"] else "OK"
                body = r["text"] or (f"<{len(r['images'])} image(s)>" if r["images"] else "(no text)")
                self._log(f"[{tag}] {label}:\n{body}")
                return r
            except Exception as e:
                self._log(f"[FAIL] {label}: {e}")
                return None

        threading.Thread(target=worker, daemon=True).start()

    def _button(self, label, cb):
        b = Gtk.Button(label=label)
        b.connect("clicked", lambda _b: cb())
        return b

    def _labeled_entry(self, placeholder):
        e = Gtk.Entry()
        e.set_placeholder_text(placeholder)
        e.set_hexpand(True)
        return e

    def _vbox(self):
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        b.set_margin_top(10); b.set_margin_bottom(10)
        b.set_margin_start(10); b.set_margin_end(10)
        return b

    def _row(self, *widgets):
        b = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        for w in widgets:
            b.append(w)
        return b

    # ---- tabs ----
    def _tests_tab(self):
        box = self._vbox()
        box.append(Gtk.Label(
            label="Runs every safe/read-only tool against the phone and reports PASS/FAIL.",
            xalign=0, wrap=True))
        results = Gtk.TextView(editable=False, monospace=True)
        sc = Gtk.ScrolledWindow(); sc.set_vexpand(True); sc.set_child(results)

        def run_all():
            buf = results.get_buffer()
            GLib.idle_add(lambda: buf.set_text("Running feature tests…\n"))

            def worker():
                passed = failed = 0
                for tool, args in FEATURE_TESTS:
                    label = f"{tool}({args.get('action') or args.get('query') or ''})".rstrip("()")
                    try:
                        r = self.client.call(tool, args, timeout=25)
                        if r["isError"]:
                            line = f"✗ {label}: tool error — {r['text'][:60]}"
                            failed += 1
                        else:
                            snippet = (r["text"][:60].replace("\n", " ")
                                       or (f"{len(r['images'])} image(s)" if r["images"] else "ok"))
                            line = f"✓ {label}: {snippet}"
                            passed += 1
                    except Exception as e:
                        line = f"✗ {label}: {e}"
                        failed += 1
                    GLib.idle_add(lambda l=line: buf.insert(buf.get_end_iter(), l + "\n"))
                summary = f"\n{passed} passed, {failed} failed of {len(FEATURE_TESTS)}."
                GLib.idle_add(lambda: buf.insert(buf.get_end_iter(), summary))

            threading.Thread(target=worker, daemon=True).start()

        box.append(self._button("▶ Run all feature tests", run_all))
        box.append(sc)
        return box

    def _device_tab(self):
        box = self._vbox()
        box.append(self._row(
            self._button("Device info", lambda: self._run("get_device_info", {})),
            self._button("Detailed status", lambda: self._run("get_detailed_status", {})),
            self._button("Notifications", lambda: self._run("list_notifications", {})),
        ))
        box.append(self._row(
            self._button("Ring", lambda: self._run("ring_device", {"action": "start"}, "ring start")),
            self._button("Stop ring", lambda: self._run("ring_device", {"action": "stop"}, "ring stop")),
            self._button("Installed apps", lambda: self._run("list_installed_apps", {})),
        ))
        # deep link + start app
        link = self._labeled_entry("deep link e.g. geo:0,0?q=cafe")
        box.append(self._row(link, self._button(
            "Open link", lambda: self._run("open_deep_link", {"uri": link.get_text()}))))
        pkg = self._labeled_entry("package e.g. com.android.settings")
        box.append(self._row(pkg, self._button(
            "Start app", lambda: self._run("start_app", {"packageName": pkg.get_text()}))))
        return box

    def _messaging_tab(self):
        box = self._vbox()
        num = self._labeled_entry("phone number")
        msg = self._labeled_entry("message")
        box.append(Gtk.Label(label="Send SMS", xalign=0))
        box.append(self._row(num, msg, self._button(
            "Send", lambda: self._run("send_sms",
                                      {"phoneNumber": num.get_text(), "message": msg.get_text()}))))
        box.append(self._button("SMS history", lambda: self._run("get_sms_history", {"limit": 10})))
        q = self._labeled_entry("contact name")
        box.append(Gtk.Label(label="Contacts", xalign=0))
        box.append(self._row(q, self._button(
            "Search", lambda: self._run("search_contacts", {"query": q.get_text()}))))
        # reply to notification
        key = self._labeled_entry("notification key")
        rep = self._labeled_entry("reply text")
        box.append(Gtk.Label(label="Reply to notification", xalign=0))
        box.append(self._row(key, rep, self._button(
            "Reply", lambda: self._run("reply_to_notification",
                                       {"notificationKey": key.get_text(), "replyText": rep.get_text()}))))
        return box

    def _screen_tab(self):
        box = self._vbox()
        box.append(self._button("Read screen", lambda: self._run("read_screen", {})))
        x = self._labeled_entry("x"); y = self._labeled_entry("y")
        box.append(Gtk.Label(label="Tap", xalign=0))
        box.append(self._row(x, y, self._button(
            "Tap", lambda: self._run("tap", {"x": _int(x), "y": _int(y)}))))
        txt = self._labeled_entry("text to type")
        box.append(self._row(txt, self._button(
            "Input text", lambda: self._run("input_text", {"text": txt.get_text()}))))
        box.append(Gtk.Label(label="Media", xalign=0))
        box.append(self._row(
            self._button("Media status", lambda: self._run("get_media_status", {})),
            self._button("Play", lambda: self._run("control_media", {"command": "play"})),
            self._button("Pause", lambda: self._run("control_media", {"command": "pause"})),
            self._button("Skip", lambda: self._run("control_media", {"command": "skip"})),
        ))
        clip = self._labeled_entry("clipboard text")
        box.append(self._row(
            clip,
            self._button("Set clip", lambda: self._run("set_clipboard", {"text": clip.get_text()})),
            self._button("Get clip", lambda: self._run("get_clipboard", {})),
        ))
        return box

    def _camera_tab(self):
        box = self._vbox()
        self.picture = Gtk.Picture()
        self.picture.set_vexpand(True)

        def snap():
            self._log("→ take_picture …")

            def worker():
                try:
                    r = self.client.call("take_picture", {}, timeout=25)
                    if r["images"]:
                        data = base64.b64decode(r["images"][0])
                        GLib.idle_add(self._show_image, data)
                        self._log(f"[OK] take_picture: {len(data)} bytes")
                    else:
                        self._log(f"[ERR] take_picture: {r['text']}")
                except Exception as e:
                    self._log(f"[FAIL] take_picture: {e}")

            threading.Thread(target=worker, daemon=True).start()

        box.append(self._button("📷 Take picture", snap))
        box.append(self.picture)
        return box

    def _show_image(self, jpeg_bytes):
        try:
            texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(jpeg_bytes))
            self.picture.set_paintable(texture)
        except Exception as e:
            self._log(f"image display failed: {e}")


def _int(entry):
    try:
        return int(entry.get_text())
    except ValueError:
        return 0


if __name__ == "__main__":
    LxConnect().run(None)
