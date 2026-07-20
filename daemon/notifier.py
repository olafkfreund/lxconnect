"""Rich desktop notifications over the freedesktop D-Bus interface.

Replaces shelling out to notify-send, which cannot set replaces_id, attach
images, or receive an inline reply. Everything here talks to
org.freedesktop.Notifications directly through Gio, which PyGObject already
provides for the GTK client.

Capabilities are negotiated at runtime: a server advertising 'inline-reply'
gets a real text field in the popup, one that doesn't falls back to a Reply
button, and one without 'body-markup' gets plain text.
"""

import os
import threading
from html import escape

CACHE_DIR = os.path.expanduser("~/.cache/lxconnect/icons")

BUS_NAME = "org.freedesktop.Notifications"
BUS_PATH = "/org/freedesktop/Notifications"

# Ceilings so a long-lived daemon can't grow without bound. A notification
# server that never emits NotificationClosed would otherwise leak one map entry
# per notification, and every mirrored image would stay on disk forever.
MAX_TRACKED = 512
MAX_CACHED_IMAGES = 64



class Notifier:
    """Posts phone notifications to the desktop and routes clicks back.

    `call_tool` is a blocking `(name, arguments) -> result-dict` used for the
    reply/activate round trips; `fetch_image` is
    `(tool, arguments) -> bytes|None` for icons. Both are injected so this
    module is testable without a phone or a bus.
    """

    APP_NAME = "lxconnect"

    def __init__(self, call_tool, fetch_image=None, bus=None):
        self._call_tool = call_tool
        self._fetch_image = fetch_image
        self._bus = bus
        self._caps = None
        self._ready = threading.Event()
        self._lock = threading.Lock()          # guards the id maps
        self._notify_lock = threading.Lock()   # serializes post-and-record
        # A phone notification updates in place (typing indicators, edits), so
        # keep its desktop id and reuse it as replaces_id instead of stacking.
        self._key_to_id = {}
        self._id_to_key = {}
        self._loop = None

    # --- lifecycle ---------------------------------------------------------

    def start(self, timeout=5.0):
        """Connects to the session bus and starts dispatching its signals.

        Returns True once the notification server answered. False means no
        usable bus — the caller should fall back to printing.
        """
        if self._bus is not None:            # injected in tests
            self._ready.set()
            return True
        threading.Thread(target=self._run_bus, daemon=True).start()
        return self._ready.wait(timeout)

    def _run_bus(self):
        try:
            from gi.repository import Gio, GLib
        except Exception as e:
            print(f"D-Bus notifications unavailable ({e}).")
            return
        try:
            self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            for signal in ("ActionInvoked", "NotificationReplied", "NotificationClosed"):
                self._bus.signal_subscribe(
                    None, BUS_NAME, signal, BUS_PATH, None,
                    Gio.DBusSignalFlags.NONE, self._on_signal, None,
                )
            self._ready.set()
            self._loop = GLib.MainLoop()
            self._loop.run()
        except Exception as e:
            print(f"D-Bus notifications unavailable ({e}).")
            self._ready.set()

    def capabilities(self):
        if self._caps is None:
            try:
                result = self._call_bus("GetCapabilities", "()", (), "(as)")
                self._caps = set(result[0]) if result else set()
            except Exception:
                self._caps = set()
        return self._caps

    def _call_bus(self, method, in_sig, args, out_sig):
        from gi.repository import Gio, GLib
        variant = GLib.Variant(in_sig, args)
        reply = self._bus.call_sync(
            BUS_NAME, BUS_PATH, BUS_NAME, method, variant,
            GLib.VariantType(out_sig), Gio.DBusCallFlags.NONE, -1, None,
        )
        return reply.unpack() if reply else None

    # --- posting -----------------------------------------------------------

    def build(self, notif, caps):
        """Assembles the Notify() arguments. Pure — the unit tests call this."""
        caps = caps or set()
        app = notif.get("appLabel") or notif.get("packageName") or "Android"
        title = notif.get("title") or app
        markup = "body-markup" in caps

        summary = (notif.get("titleMarkup") if markup else None) or title
        body = (notif.get("textMarkup") if markup else None) or notif.get("text") or ""

        if markup and notif.get("urls") and "body-hyperlinks" in caps:
            # Links only present in the intent (not the visible text) would
            # otherwise be unreachable; append the ones we found as spans.
            # The URLs come from the phone, so they are escaped before being
            # embedded -- an unescaped quote would break out of href="...".
            shown = [u for u in notif["urls"] if escape(u) not in body]
            if shown:
                links = " ".join(
                    f'<a href="{escape(u, quote=True)}">{escape(u)}</a>' for u in shown)
                body = f"{body}\n{links}" if body else links

        actions = []
        if "actions" in caps:
            # The spec's "default" action is what firing the popup's body does.
            actions += ["default", "Open on phone"]
            if self._reply_action(notif):
                # Servers render the inline text field for the "inline-reply"
                # action id; the placeholder hint alone does nothing. Without
                # this the field never appears and the fallback button is
                # suppressed, leaving no way to reply at all.
                actions += (["inline-reply", "Reply"] if "inline-reply" in caps
                            else ["reply", "Reply"])
            for action in notif.get("actions", []):
                if action.get("isReply"):
                    continue
                label = action.get("title") or f"Action {action.get('index')}"
                actions += [f"action-{action.get('index')}", label]

        hints = {"desktop-entry": "lxconnect"}
        if notif.get("category") == "call":
            hints["urgency"] = 2
        if self._reply_action(notif) and "inline-reply" in caps:
            hints["x-kde-reply-placeholder-text"] = f"Reply to {title}"

        return summary, body, actions, hints

    @staticmethod
    def _reply_action(notif):
        return any(a.get("isReply") for a in notif.get("actions", []))

    def notify(self, notif):
        """Posts (or updates) the desktop notification for a phone notification."""
        caps = self.capabilities()
        summary, body, actions, hints = self.build(notif, caps)

        icon = ""
        if "icon-static" in caps:
            icon = self._app_icon(notif.get("packageName", "")) or ""
        if "body-images" in caps:
            image = self._notification_image(notif)
            if image:
                hints["image-path"] = image

        key = notif.get("key", "")
        # Reading replaces_id, posting, and storing the new id must be one
        # atomic step: two updates to the same phone notification racing here
        # both saw no existing id and posted two popups instead of updating one.
        # Images were fetched above so this holds only across a local call.
        with self._notify_lock:
            with self._lock:
                replaces = self._key_to_id.get(key, 0)
            try:
                result = self._call_bus(
                    "Notify", "(susssasa{sv}i)",
                    (self.APP_NAME, replaces, icon, summary, body, actions,
                     {k: self._variant(v) for k, v in hints.items()}, -1),
                    "(u)",
                )
            except Exception as e:
                print(f"Desktop notification failed ({e}): {summary} — {body}")
                return None
            notif_id = result[0] if result else 0
            if notif_id and key:
                with self._lock:
                    self._key_to_id[key] = notif_id
                    self._id_to_key[notif_id] = key
                    self._forget_oldest()
        return notif_id

    def _forget_oldest(self):
        """Bounds the id maps. Caller holds self._lock."""
        while len(self._key_to_id) > MAX_TRACKED:
            old_key = next(iter(self._key_to_id))          # insertion ordered
            self._id_to_key.pop(self._key_to_id.pop(old_key), None)

    @staticmethod
    def _variant(value):
        from gi.repository import GLib
        if isinstance(value, bool):
            return GLib.Variant("b", value)
        if isinstance(value, int):
            return GLib.Variant("y", value) if 0 <= value <= 255 else GLib.Variant("i", value)
        return GLib.Variant("s", str(value))

    def close(self, key):
        """Drops the desktop popup for a notification dismissed on the phone."""
        with self._lock:
            notif_id = self._key_to_id.pop(key, None)
            self._id_to_key.pop(notif_id, None)
        if not notif_id:
            return
        try:
            self._call_bus("CloseNotification", "(u)", (notif_id,), "()")
        except Exception:
            pass

    # --- images ------------------------------------------------------------

    def _app_icon(self, package):
        """Path to the app's launcher icon, fetched once and cached on disk."""
        if not package or not self._fetch_image:
            return None
        path = os.path.join(CACHE_DIR, f"{package}.png")
        if os.path.exists(path):
            return path
        data = self._fetch_image("get_app_icon", {"packageName": package})
        return self._write_cache(path, data)

    def _notification_image(self, notif):
        """The BigPicture image if there is one, else the sender's avatar."""
        if not self._fetch_image:
            return None
        which = "picture" if notif.get("hasPicture") else (
            "largeIcon" if notif.get("hasLargeIcon") else None)
        if not which:
            return None
        key = notif.get("key", "")
        safe = "".join(c if c.isalnum() else "_" for c in key)[:120]
        path = os.path.join(CACHE_DIR, f"n_{safe}_{which}.png")
        data = self._fetch_image(
            "get_notification_image", {"notificationKey": key, "which": which})
        written = self._write_cache(path, data)
        if written:
            _trim_image_cache()
        return written

    @staticmethod
    def _write_cache(path, data):
        if not data:
            return None
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            return path
        except Exception:
            return None

    # --- incoming signals --------------------------------------------------

    def _on_signal(self, conn, sender, path, iface, signal, params, user_data):
        try:
            self.handle_signal(signal, list(params.unpack()))
        except Exception as e:
            print(f"Error handling {signal}: {e}")

    def handle_signal(self, signal, args):
        """Routes a desktop interaction back to the phone. Pure enough to test."""
        if not args:
            return
        notif_id = args[0]
        with self._lock:
            key = self._id_to_key.get(notif_id)
        if not key:
            return

        if signal == "NotificationReplied" and len(args) > 1 and args[1]:
            self._call_tool("reply_to_notification",
                            {"notificationKey": key, "replyText": args[1]})
        elif signal == "ActionInvoked" and len(args) > 1:
            action = args[1]
            if action == "default":
                self._call_tool("activate_notification", {"notificationKey": key})
            elif action == "reply":
                text = _prompt_for_reply()
                if text:
                    self._call_tool("reply_to_notification",
                                    {"notificationKey": key, "replyText": text})
            elif action.startswith("action-"):
                self._call_tool("invoke_notification_action",
                                {"notificationKey": key,
                                 "actionIndex": int(action.split("-", 1)[1])})
        elif signal == "NotificationClosed":
            with self._lock:
                self._id_to_key.pop(notif_id, None)
                if self._key_to_id.get(key) == notif_id:
                    self._key_to_id.pop(key, None)


def _trim_image_cache(limit=MAX_CACHED_IMAGES):
    """Keeps the newest per-notification images and drops the rest.

    App icons ("<package>.png") are bounded by how many apps you have and are
    left alone; notification images are keyed per notification instance, so
    without this every avatar and photo ever mirrored stays on disk forever.
    """
    try:
        files = [os.path.join(CACHE_DIR, f) for f in os.listdir(CACHE_DIR)
                 if f.startswith("n_")]
        if len(files) <= limit:
            return
        files.sort(key=os.path.getmtime, reverse=True)
        for stale in files[limit:]:
            try:
                os.remove(stale)
            except OSError:
                pass
    except OSError:
        pass


def _prompt_for_reply():
    """Fallback text entry for servers without inline-reply."""
    import subprocess
    try:
        proc = subprocess.run(
            ["zenity", "--entry", "--title=lxconnect Reply", "--text=Reply:"],
            capture_output=True, text=True, timeout=300)
        return proc.stdout.strip() if proc.returncode == 0 else None
    except Exception:
        return None


def simple(summary, body, urgency=None):
    """One-off desktop message not tied to a phone notification."""
    import subprocess
    try:
        args = ["notify-send", "-a", Notifier.APP_NAME]
        if urgency:
            args += ["-u", urgency]
        subprocess.run(args + [summary, body])
    except Exception as e:
        print(f"{summary}: {body} ({e})")
