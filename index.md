---
layout: default
---

# lxconnect 🚀

**Android to Linux Desktop Bridge** for Waydroid and Native Android environments.

## Features
- **MCP Server:** Exposes Android capabilities (Files, Notifications, Intents, Apps) to your Linux environment.
- **Deep Integration:** Open Android deep links natively (`mailto:`, `spotify:`), control Android apps, read Android system status.
- **Background Daemon:** Lightning fast SSE stream bridging Android OS and NixOS.
- **Accessibility Control:** Drive arbitrary app UIs — tap, swipe, type, read the screen.
- **Secure by Default:** HTTPS with a pinned self-signed certificate, paired over a QR code.

## Usage

Since `lxconnect` is fully packaged as a declarative Nix Flake, you can run the CLI directly from the repository without installing anything:

```bash
# Pair with your phone (prints a QR code / deep link to open on the device)
nix run github:olafkfreund/lxconnect -- pair

# Run the background daemon that bridges phone notifications to your desktop
nix run github:olafkfreund/lxconnect -- daemon
```

## Architecture

1. **Android App:** Runs a Ktor MCP Server on port 8080. Connects to `NotificationListenerService` and `PackageManager` to bypass Android sandboxes.
2. **Daemon CLI:** A Python HTTP daemon (`lxconnect daemon`) that reads Server-Sent Events (SSE) from the Android app and integrates with `libnotify` for Linux desktop notifications.
3. **Secure Transport:** TLS is terminated on-device by a Conscrypt `SSLServerSocket`; the daemon pins the phone's self-signed certificate, whose fingerprint is exchanged at pairing time.
