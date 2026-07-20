# lxconnect

**Android to Linux Desktop Bridge — an agent-controllable phone over pinned TLS.**

lxconnect turns an Android phone into something you, or an AI agent, can drive from
your Linux desktop: read and reply to notifications, send SMS, search contacts,
control media, take a photo, move files, and tap or type in arbitrary apps. It is
not another KDE Connect clone — the phone runs a **Model Context Protocol (MCP)
server**, so the client on the other end can be a CLI, a GTK app, or a language
model.

[Website](https://olafkfreund.github.io/lxconnect/) ·
[Documentation](https://olafkfreund.github.io/lxconnect/documentation.html) ·
[Releases](https://github.com/olafkfreund/lxconnect/releases/latest) ·
[Blog post](https://olafkfreund.github.io/lxconnect/2026/07/17/building-lxconnect.html)

---

## Overview

Three components:

| Component | What it does |
| --- | --- |
| Android app | Runs an on-device MCP server exposing ~24 typed tools plus an AccessibilityService for driving arbitrary app UIs. |
| Python daemon | Bridges the phone to the desktop — notification mirroring, inline reply, tool dispatch, and trigger-based automations. |
| GTK4 client | A proof-of-concept native desktop app that demonstrates every feature and runs a full self-test suite against the phone. |

Everything has been verified end to end on real hardware.

## Features

- **MCP server on the phone.** Around 24 typed tools: SMS send and history,
  notification listing and reply, contact search, media status and control,
  device and detailed status, ring, camera capture, clipboard, file transfer,
  app launch and stop, and deep links.
- **Rich notification mirroring.** Phone notifications arrive on the desktop
  looking native: real app name and icon, the full expanded body, bold/italic and
  clickable links, inline images and sender avatars, inline reply, the app's own
  action buttons, and click-to-open which resumes that exact screen on the phone.
  They update in place instead of stacking. Capabilities are negotiated with your
  notification server, so it degrades cleanly.
- **Accessibility control.** Tap, swipe, type, read the screen, and screenshot —
  so an agent can drive any app, not just the ones wrapped as tools.
- **Secure by default.** HTTPS on the device with a self-signed certificate that
  the client pins by SHA-256 fingerprint; the fingerprint is exchanged during a
  QR-based, trust-on-first-use pairing.
- **Automations.** Rules match incoming notifications on app, title, or text
  (substring or regex) and fire actions: ring the phone, reply, call any tool, or
  raise a desktop notification.
- **GTK4 proof-of-concept client.** Per-feature tabs and a one-click "Run all
  feature tests" that exercises the safe tool surface against the phone. It
  demonstrates the MCP surface rather than being a polished product; see the
  [documentation](https://olafkfreund.github.io/lxconnect/documentation.html) for
  the full tool reference.
- **Reproducible.** Packaged as a Nix flake, with CI and signed releases.

## Architecture

1. **Android app.** A Ktor MCP server bound to localhost, fronted by a Conscrypt
   `SSLServerSocket` that terminates TLS on port 8080. It hooks
   `NotificationListenerService`, `PackageManager`, and an AccessibilityService.
2. **Python daemon.** Holds a Server-Sent Events session to the phone, mirrors
   notifications to `libnotify`, dispatches tool calls, and runs automations.
3. **Secure transport.** The daemon pins the phone's self-signed certificate by
   fingerprint. Pairing exchanges that fingerprint over its own pinned-TLS
   channel, so the shared secret never travels in cleartext.

### Why a separate TLS terminator

Ktor's Netty engine wraps Android's Conscrypt `SSLEngine` and throws an
`AssertionError` mid-handshake on real hardware; the CIO engine does not support
server HTTPS; Jetty needs a Java 9 `ServiceLoader` API that Android's runtime
lacks. The working design keeps Netty on plaintext localhost and terminates TLS
with `javax.net.ssl.SSLServerSocket` — the platform's own, reliable TLS stack —
while keeping the exact same certificate and pinning.

## Security

lxconnect ships with an on-device HTTPS data plane, pinned certificates, a
constant-time bearer token, backup exclusion of secrets, and a confirmation
dialog on every pairing request (an earlier build allowed a zero-interaction
pairing-hijack from any installed app; that is fixed and verified). See the
`v1.0.1` release notes for the full security-audit changelog.

## Install

Download the latest signed APK from the
[releases page](https://github.com/olafkfreund/lxconnect/releases/latest), install
it, then grant notification and accessibility access and pair.

## Usage

The project is packaged as a Nix flake, so you can run it without installing
anything:

```bash
# Pair with your phone (prints a QR code / deep link to open on the device)
nix run github:olafkfreund/lxconnect -- pair

# Run the background daemon that bridges phone notifications to your desktop
nix run github:olafkfreund/lxconnect -- daemon

# Launch the GTK desktop client (drive every feature + run a self-test suite)
nix run github:olafkfreund/lxconnect#gui

# Summarize active notifications, grouped by app
nix run github:olafkfreund/lxconnect -- triage
```

### Automations

Create `~/.config/lxconnect/rules.json`:

```json
{
  "rules": [
    {
      "name": "Boss on WhatsApp",
      "match": { "package": "whatsapp", "text": "boss" },
      "actions": [
        { "tool": "ring_device", "arguments": { "action": "start" } },
        { "notify": "{title}: {text}" }
      ]
    }
  ]
}
```

Match fields are ANDed; an absent field matches anything, so `{}` is a catch-all.
Set `"regex": true` in a rule's match to treat patterns as regular expressions.
List loaded rules with `lxconnect rules`.

## Development

```bash
# Dev shell with the Android SDK, JDK, and tooling
nix develop

# Build the debug APK (inside the dev shell)
./gradlew assembleDebug

# Run the daemon test suite
cd daemon && python -m unittest discover -p "test_*.py"
```

CI runs on every pull request: the Android build, the daemon unit tests, and a
Nix flake check. Tagging `v*` builds a signed APK and publishes a GitHub Release.

## License

See the repository for license details.
