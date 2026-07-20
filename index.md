---
layout: default
---

# lxconnect

**Android to Linux Desktop Bridge** for Waydroid and Native Android environments — control your phone (or let an AI agent control it) from your Linux desktop over a pinned-TLS MCP connection.

[**Download the latest signed APK**](https://github.com/olafkfreund/lxconnect/releases/latest) · [Source on GitHub](https://github.com/olafkfreund/lxconnect)

## Features
- **MCP Server:** Exposes Android capabilities (Files, Notifications, Intents, Apps) to your Linux environment.
- **Deep Integration:** Open Android deep links natively (`mailto:`, `spotify:`), control Android apps, read Android system status.
- **Background Daemon:** Lightning fast SSE stream bridging Android OS and NixOS.
- **Rich Notifications:** Mirrored notifications look native — real app name and icon, full body, formatted text with clickable links, inline images, inline reply, action buttons, and click-to-open that resumes the app on the phone.
- **Accessibility Control:** Drive arbitrary app UIs — tap, swipe, type, read the screen.
- **GTK4 Proof-of-Concept Client:** A native Linux app demonstrating every capability — drive the phone interactively and run a full self-test suite against it. See the [Documentation](documentation.html) for the complete MCP tool reference and a setup walkthrough.
- **Automations:** Trigger-based rules — when a matching notification arrives (by app/title/text), ring the phone, reply, or fire any tool. Plus `lxconnect triage` to summarize notifications by app.
- **Secure by Default:** HTTPS with a pinned self-signed certificate, paired over a QR code.

## Usage

Since `lxconnect` is fully packaged as a declarative Nix Flake, you can run the CLI directly from the repository without installing anything:

```bash
# Pair with your phone (prints a QR code / deep link to open on the device)
nix run github:olafkfreund/lxconnect -- pair

# Run the background daemon that bridges phone notifications to your desktop
nix run github:olafkfreund/lxconnect -- daemon

# Or launch the GTK desktop client (drive every feature + run a self-test suite)
nix run github:olafkfreund/lxconnect#gui
```

## Architecture

1. **Android App:** Runs a Ktor MCP Server on port 8080. Connects to `NotificationListenerService` and `PackageManager` to bypass Android sandboxes.
2. **Daemon CLI:** A Python HTTP daemon (`lxconnect daemon`) that reads Server-Sent Events (SSE) from the Android app and integrates with `libnotify` for Linux desktop notifications.
3. **Secure Transport:** TLS is terminated on-device by a Conscrypt `SSLServerSocket`; the daemon pins the phone's self-signed certificate, whose fingerprint is exchanged at pairing time.

## Blog

<ul>
{% for post in site.posts %}
  <li><a href="{{ post.url | relative_url }}">{{ post.title }}</a> <em>({{ post.date | date: "%Y-%m-%d" }})</em></li>
{% endfor %}
</ul>

