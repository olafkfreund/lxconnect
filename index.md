---
layout: default
---

# lxconnect

**Android to Linux Desktop Bridge** for Waydroid and Native Android environments — control your phone (or let an AI agent control it) from your Linux desktop over a pinned-TLS MCP connection.

[**Download the latest signed APK**](https://github.com/olafkfreund/lxconnect/releases/latest) · [Source on GitHub](https://github.com/olafkfreund/lxconnect)

## Features
- **MCP Server:** 34 typed tools on the phone — notifications, SMS, contacts, media, camera, files, apps, clipboard, and screen control — callable by a CLI, a GTK app, or a language model.
- **Rich Notifications:** Mirrored notifications look native — real app name and icon, full body, formatted text with clickable links, inline images, inline reply, action buttons, and click-to-open that resumes the app on the phone. They update in place instead of stacking.
- **Accessibility Control:** Drive arbitrary app UIs — read the screen, tap, swipe, type, screenshot. An agent can use an app lxconnect knows nothing about.
- **On-Device Testing:** Drive real test flows against a phone or tablet with no cable. `tap_text` and `wait_for` select by text or view id instead of brittle coordinates, `press_key` navigates back/home/recents, and `http_request` probes a service from the device's own network, TLS stack and egress IP.
- **Deep Integration:** Open Android deep links natively (`mailto:`, `spotify:`), launch and stop apps, read detailed system status.
- **Automations:** Trigger-based rules — when a matching notification arrives (by app/title/text), ring the phone, reply, or fire any tool. Plus `lxconnect triage` to summarize notifications by app.
- **GTK4 Proof-of-Concept Client:** A native Linux app demonstrating every capability — drive the phone interactively and run a full self-test suite against it. See the [Documentation](documentation.html) for the complete MCP tool reference and a setup walkthrough.
- **Secure by Default:** HTTPS with a pinned self-signed certificate, paired over a QR code.

## Use cases

- **Your phone from your desk.** Read and answer messages, press an app's own notification buttons, open a conversation on the phone with one click, ring it when it's lost.
- **A phone an agent can use.** *"Summarize my unread notifications and tell Alex I'll be home by six"* is a short sequence of typed tool calls — not screen-scraping.
- **QA on a real device.** `start_app` → `wait_for` → `tap_text` → `screenshot` drives a test flow on a real OEM device, over a tailnet if you like, without wiring up adb.
- **Testing a service as a phone sees it.** `http_request` runs from the device, catching carrier NAT, mobile routing and TLS failures a desktop `curl` cannot reproduce.

See the [Documentation](documentation.html#use-cases) for each of these worked through end to end.

## Usage

Since `lxconnect` is fully packaged as a declarative Nix Flake, you can run the CLI directly from the repository without installing anything:

```bash
# Pair with your phone (prints a QR code / deep link to open on the device)
nix run github:olafkfreund/lxconnect -- pair

# Run the background daemon that bridges phone notifications to your desktop
nix run github:olafkfreund/lxconnect -- daemon

# Summarize active notifications, grouped by app
nix run github:olafkfreund/lxconnect -- triage

# Or launch the GTK desktop client (drive every feature + run a self-test suite)
nix run github:olafkfreund/lxconnect#gui
```

## Architecture

1. **Android App:** Runs a Ktor MCP server on localhost, fronted by a TLS terminator on port 8080. Hooks `NotificationListenerService`, `PackageManager`, and an AccessibilityService.
2. **Daemon CLI:** A Python HTTP daemon (`lxconnect daemon`) that reads Server-Sent Events (SSE) from the Android app and posts rich Linux desktop notifications over D-Bus.
3. **Secure Transport:** TLS is terminated on-device by a Conscrypt `SSLServerSocket`; the daemon pins the phone's self-signed certificate, whose fingerprint is exchanged at pairing time.

## Blog

<ul>
{% for post in site.posts %}
  <li><a href="{{ post.url | relative_url }}">{{ post.title }}</a> <em>({{ post.date | date: "%Y-%m-%d" }})</em></li>
{% endfor %}
</ul>

