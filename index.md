---
layout: default
---

# lxconnect 🚀

**Android to Linux Desktop Bridge** for Waydroid and Native Android environments.

## Features
- **MCP Server:** Exposes Android capabilities (Files, Notifications, Intents, Apps) to your Linux environment.
- **Deep Integration:** Open Android deep links natively (`mailto:`, `spotify:`), control Android apps, read Android system status.
- **Background Daemon:** Lightning fast SSE stream bridging Android OS and NixOS.
- **GTK4 App:** Beautiful native UI to test and control your Android device.

## Usage

Since `lxconnect` is fully packaged as a declarative Nix Flake, you can launch the GTK4 UI directly from the repository without installing anything:

```bash
nix run github:olafkfreund/lxconnect#gui
```

## Architecture

1. **Android App:** Runs a Ktor MCP Server on port 8080. Connects to `NotificationListenerService` and `PackageManager` to bypass Android sandboxes.
2. **Daemon CLI:** A Python HTTP daemon (`lxconnect daemon`) that reads Server-Sent Events (SSE) from the Android app and integrates with `libnotify` for Linux desktop notifications.
3. **GTK4 UI:** Uses PyGObject and `wrapGAppsHook4` for an uncompromised native Linux experience.
