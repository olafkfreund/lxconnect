---
layout: default
title: Documentation
---

# Documentation

lxconnect runs a **Model Context Protocol (MCP) server on your Android phone** and
lets a client on your Linux desktop call its tools over a pinned-TLS connection.
The client can be the command line, the GTK proof-of-concept app, or a language
model. This page covers what the server exposes, how to set it up, and how to use
it, with a real-life walkthrough at the end.

## What the MCP server gives you

The phone exposes about thirty-five **tools** — typed, named operations an
authenticated client can invoke. They fall into a few groups: messaging and
notifications, device status, media, camera, apps and navigation, files,
contacts, clipboard, and screen control through the AccessibilityService.

Transport is a single HTTPS endpoint on the phone (`https://<phone>:8080/`). A
client opens a Server-Sent Events session, completes the MCP `initialize`
handshake, then POSTs `tools/call` requests; results stream back on the SSE
channel. Every request carries a bearer token, and the client pins the phone's
self-signed certificate by fingerprint.

## Setup

### 1. Install the app

Download the latest signed APK from the
[releases page](https://github.com/olafkfreund/lxconnect/releases/latest) and
install it on the phone.

### 2. Grant permissions

Open the app and grant, using its buttons:

- **Runtime permissions** — SMS, contacts, phone state, camera, notifications.
- **Notification Listener access** — required to list and reply to notifications
  and to read media sessions.
- **Accessibility access** — required for `read_screen`, `tap`, `swipe`,
  `input_text`, and `screenshot`.
- **Disable battery optimization** — so the foreground server survives aggressive
  OEM battery management.

Reinstalling the app resets the Notification Listener and Accessibility grants, so
re-enable them after an update.

### 3. Pair

On the desktop:

```bash
nix run github:olafkfreund/lxconnect -- pair
```

This prints a QR code and an `lxconnect://pair` link, and starts a short-lived
HTTPS pairing server. Open the link on the phone (or scan the QR); the phone shows
a confirmation dialog naming the desktop, and on confirmation exchanges its
certificate fingerprint over the pinned pairing channel. The desktop writes
`~/.config/lxconnect/config.json` (mode 0600) with the phone's IP, the shared
secret, and the pinned fingerprint.

To re-pair later, just run `pair` again; a running daemon picks up the new
configuration automatically.

## Using the clients

### Command line

```bash
nix run github:olafkfreund/lxconnect -- daemon     # bridge notifications to the desktop
nix run github:olafkfreund/lxconnect -- status     # detailed device status
nix run github:olafkfreund/lxconnect -- triage      # notifications grouped by app
nix run github:olafkfreund/lxconnect -- ring start  # find your phone
```

The `daemon` holds the live session and mirrors incoming phone notifications to
your desktop as **native rich notifications**.

### Rich notifications

Mirrored notifications are not plain text. The daemon talks to
`org.freedesktop.Notifications` over D-Bus directly and negotiates what your
notification server supports, so a mirrored notification looks like a local one:

- **The real app identity** — "WhatsApp", not `com.whatsapp`, with the app's own
  launcher icon (fetched once per app and cached in `~/.cache/lxconnect/icons`).
- **The full body**, not the collapsed preview: expanded BigText, and chat
  notifications rendered as `Sender: message` per line.
- **Formatting and links** — bold, italic, underline and clickable URLs, carried
  across from the phone's own styled text (requires `body-markup` /
  `body-hyperlinks`).
- **Images** — a message's photo or the sender's avatar, shown inline (requires
  `body-images`).
- **Inline reply** — type straight into the notification and it goes back to the
  phone (requires `inline-reply`; otherwise you get a Reply button that opens a
  text prompt).
- **Click to open on the phone** — clicking the notification body fires its
  content intent, so the phone jumps to that exact conversation or screen.
- **The app's own buttons** — "Mark as read", "Archive" and friends appear as
  desktop actions.
- **Updates in place.** A phone notification that updates (typing indicators,
  edited messages) replaces its desktop popup instead of stacking a new one, and
  dismissing it on the phone retracts it from the desktop.

Ongoing notifications (media players, downloads, foreground services) and group
summary rows are filtered out, since they re-post constantly and aren't
actionable.

Check what your notification server supports with:

```bash
gdbus call --session --dest org.freedesktop.Notifications \
  --object-path /org/freedesktop/Notifications \
  --method org.freedesktop.Notifications.GetCapabilities
```

### GTK proof-of-concept client

```bash
nix run github:olafkfreund/lxconnect#gui
```

This is a **proof-of-concept** client — it demonstrates the full surface of the
MCP server rather than being a polished product. It is a complete MCP client in
its own right (its own pinned-TLS session and handshake), organized into tabs:

- **Tests** — a "Run all feature tests" button that calls every safe, read-only
  tool in sequence and reports PASS/FAIL. The fastest way to confirm a working
  setup.
- **Device** — device info, detailed status, notifications, ring, list installed
  apps, open a deep link, launch an app.
- **Messaging** — send an SMS, read SMS history, search contacts, reply to a
  notification by key.
- **Screen** — read the screen, tap at coordinates, type into the focused field,
  media controls, and clipboard get/set.
- **Camera** — capture a photo and view it inline.

An output pane at the bottom logs every call's result, and incoming phone
notifications appear there live.

### Automations

Create `~/.config/lxconnect/rules.json` to react to incoming notifications
automatically:

```json
{
  "rules": [
    {
      "name": "Partner messaging",
      "match": { "package": "signal", "title": "Alex" },
      "actions": [
        { "notify": "Alex: {text}" },
        { "tool": "ring_device", "arguments": { "action": "start" } }
      ]
    }
  ]
}
```

`match` fields (`package`, `title`, `text`) are ANDed; an absent field matches
anything, so `{}` is a catch-all. Set `"regex": true` to treat patterns as regular
expressions. Actions are either `{"tool": name, "arguments": {…}}` (any tool
below) or `{"notify": "template with {title}/{text}/{package}"}`. List loaded rules
with `lxconnect rules`.

## MCP tool reference

These are the endpoints available to any client. An asterisk marks a required
argument.

### Messaging and notifications

| Tool | Arguments | Effect |
| --- | --- | --- |
| `list_notifications` | — | List active status-bar notifications (key, app label, package, title, text). |
| `reply_to_notification` | `notificationKey`*, `replyText`* | Send an inline reply to a chat/message notification. |
| `activate_notification` | `notificationKey`* | Open the app where the notification points — the same as tapping it on the phone. |
| `invoke_notification_action` | `notificationKey`*, `actionIndex`* | Press one of the notification's action buttons ("Mark as read", "Archive"). |
| `dismiss_notification` | `notificationKey`* | Dismiss the notification on the phone. |
| `get_notification_image` | `notificationKey`*, `which` (`largeIcon`/`picture`/`appIcon`) | Fetch the sender avatar or inline image as a PNG. |
| `get_app_icon` | `packageName`* | Fetch an app's launcher icon as a PNG. |
| `send_sms` | `phoneNumber`*, `message`* | Send an SMS. |
| `get_sms_history` | `limit` (default 10, clamped 0–1000) | Read recent SMS messages. |

### Device and status

| Tool | Arguments | Effect |
| --- | --- | --- |
| `get_device_info` | — | Model, manufacturer, Android version, battery percentage, and screen size/density/orientation. |
| `get_detailed_status` | — | Battery and temperature, RAM, storage, Wi-Fi SSID/RSSI, carrier. |
| `ring_device` | `action` (`start`/`stop`) | Ring the phone at maximum volume to locate it. |

### Media and clipboard

| Tool | Arguments | Effect |
| --- | --- | --- |
| `get_media_status` | — | Active media session: player, state, title, artist, album. |
| `control_media` | `command`* (`play`/`pause`/`skip`/`previous`), `packageName` | Control playback. |
| `get_clipboard` | — | Read clipboard text (Android may block background reads). |
| `set_clipboard` | `text`* | Set the clipboard text. |

### Camera

| Tool | Arguments | Effect |
| --- | --- | --- |
| `take_picture` | — | Capture a photo from the back camera; returns a JPEG image. |

### Apps and navigation

| Tool | Arguments | Effect |
| --- | --- | --- |
| `list_installed_apps` | — | List installed apps with labels and package names. |
| `start_app` | `packageName`* | Launch an app. |
| `stop_app` | `packageName`* | Kill an app's background processes. |
| `open_deep_link` | `uri`* | Open a deep link, e.g. `spotify:search:jazz` or `geo:0,0?q=cafe`. |

### Contacts and files

| Tool | Arguments | Effect |
| --- | --- | --- |
| `search_contacts` | `query`* | Find contacts by name and return their numbers. |
| `list_files` | — | List files in the device's `Download/lxconnect` folder. |
| `send_file` | `fileName`*, `base64Data`* | Write a file to the device. |
| `get_file` | `fileName`* | Retrieve a file as base64. |

### Screen control (AccessibilityService)

| Tool | Arguments | Effect |
| --- | --- | --- |
| `read_screen` | — | Dump the UI tree of each visible window — app, keyboard, then system shade — one `window=` block each (class, view id, text, description, bounds, clickable, scrollable, enabled). |
| `tap` | `x`*, `y`* | Tap at screen coordinates. |
| `tap_text` | `query`* | Tap the element matching a text/description substring or an exact view id. Prefer this over `tap`. |
| `wait_for` | `query`*, `timeoutMs` (default 5000, max 15000) | Block until a matching element appears, or time out. Capped below the clients' own call timeouts. |
| `press_key` | `key`* (`back`/`home`/`recents`/`notifications`) | Press a system navigation key. |
| `swipe` | `x1`*, `y1`*, `x2`*, `y2`* | Swipe between two points. |
| `input_text` | `text`* | Type into the focused editable field. |
| `screenshot` | — | Capture a screenshot (Android 11+); returns an image. |

### Network

| Tool | Arguments | Effect |
| --- | --- | --- |
| `http_request` | `url`*, `method`, `headers`, `body` | Make an HTTP(S) request **from the device** — its network, its TLS stack, its egress IP. Returns status, timing, response headers and body (body truncated at 20 000 bytes). |

## Testing with lxconnect

Because the tools are typed and the device is remote, lxconnect can drive test
flows against a real tablet with no adb cable — over Tailscale if you like.

```
start_app / open_deep_link   →  put the device where the test begins
wait_for                     →  synchronise instead of guessing at render time
tap_text / input_text        →  act on elements, not coordinates
read_screen / screenshot     →  assert on what is actually displayed
press_key back               →  unwind between cases
```

Prefer `tap_text` and `wait_for` over `tap(x, y)`: coordinates break on every
layout, density or rotation change, whereas text and view ids survive a rebuild.
Call `get_device_info` if you do need the coordinate space — it reports the
display size, density and current orientation.

`http_request` reaches plain `http://` as well as HTTPS, so LAN and localhost
targets work; responses are capped at 20 000 bytes, redirects are **not**
followed (the `Location` header is reported instead, so a caller-supplied
`Authorization` never leaks to a redirect target), and the response's declared
charset is honoured. Note that any client holding the pairing key can make the
phone issue requests to any host it can reach — on a tailnet that is a wider
reach than the phone-local tools.

It covers the other half of testing: a deployed service *as the device
sees it*. The response includes the device's own `User-Agent`, and the request
carries its egress IP, so you can catch failures that a desktop `curl` cannot
reproduce — mobile-network routing, carrier NAT, or a TLS configuration Android
rejects.

**Limits worth knowing.** This is black-box testing driven by the accessibility
tree. There is no DOM, no JavaScript console, no network waterfall: Chrome's
remote-debugging socket is reachable only through adb, not from an on-device app.
On a web page the tree also contains browser chrome, so `wait_for "Example
Domain"` may match a tab title rather than page content — keep queries specific.
An app cannot read another app's logcat without adb or root, so crashes of the
app under test are not visible here, and there is no APK install tool, so
build → install → test belongs in a normal adb/CI pipeline.

`get_device_info` reports the whole physical display, so in split-screen or
freeform windowing its dimensions describe the screen rather than the app under
test. `wait_for` polls the accessibility tree every 200 ms, which on a very large
UI is not free — prefer short waits over long ones.

## A real-life scenario

You are at your desk; your phone is charging in another room.

1. A notification comes in. Your daemon mirrors it to the desktop — or you run
   `lxconnect triage` to see everything grouped by app: *"Signal: 2 — Alex, Mum;
   Slack: 1; WhatsApp: 3."*
2. Alex asks when you'll be home. You reply from the desktop without getting up —
   in the GTK client's Messaging tab, or by clicking **Reply** on the mirrored
   desktop notification (`reply_to_notification`).
3. You can't remember where you left the phone, so you hit **Ring**
   (`ring_device`) and it chimes at full volume upstairs.
4. Before a call you want to look presentable — **Take picture**
   (`take_picture`) shows the room through the back camera, inline in the client.
5. You set up an automation so this is hands-free next time: when Signal shows a
   message from Alex, raise a desktop notification and ring the phone. Drop the
   rule into `rules.json`; the running daemon picks it up.

Because the phone is an MCP server, the same tools are available to an AI agent:
"summarize my unread notifications and reply to Alex that I'll be home by six"
becomes a short sequence of `list_notifications` and `reply_to_notification`
calls — the phone is genuinely agent-controllable.

## How it fits together

The Android app runs a Ktor MCP server bound to localhost; a Conscrypt
`SSLServerSocket` terminates TLS on port 8080 and forwards to it. The Linux client
pins the phone's certificate by SHA-256 fingerprint (exchanged at pairing) and
authenticates with the shared bearer token. Nothing is sent in cleartext, and the
data plane is the same whether the client is the CLI, the GTK app, or a model.
