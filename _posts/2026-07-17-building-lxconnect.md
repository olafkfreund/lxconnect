---
layout: default
title: "Building lxconnect: an agent-controllable phone over pinned TLS"
date: 2026-07-17
---

# Building lxconnect: an agent-controllable phone over pinned TLS

*2026-07-17*

lxconnect turns an Android phone into something you — or an AI agent — can drive
from your Linux desktop: read and reply to notifications, send SMS, search
contacts, control media, snap a photo, move files, and even tap and type in
arbitrary apps. Under the hood it isn't another KDE Connect clone; the phone runs
a **Model Context Protocol (MCP) server**, so the client on the other end can be
a CLI, a GTK app, or an LLM. This post walks through what got built and the
engineering behind it.

## The shape of the thing

Three pieces:

- **An Android app** that runs an on-device MCP server exposing ~24 typed tools
  (SMS, notifications, contacts, media, camera, files) plus an
  AccessibilityService for `tap` / `swipe` / `input_text` / `read_screen` /
  `screenshot` — so an agent can drive any app UI, not just the ones we wrapped.
- **A Linux Python daemon** that bridges the phone to the desktop (notification
  mirroring, inline reply) and dispatches tool calls.
- **A GTK4 desktop client** that drives every feature and can run a full
  self-test suite against the phone.

Everything below was verified on real hardware — a Unisoc Android 14 device.

## The security spine: pinned TLS both ways

An MCP server that can send SMS and read your contacts cannot speak cleartext.
So the phone serves **HTTPS with a self-signed certificate generated on first
run**, and the daemon **pins** it by SHA-256 fingerprint. The fingerprint is
exchanged during pairing — you scan a QR code, and trust is established
trust-on-first-use.

### The Netty saga

The first surprise: Ktor's Netty engine wraps Android's Conscrypt `SSLEngine`,
and on real hardware its `SslHandler` throws `java.lang.AssertionError`
mid-handshake. HTTPS over Netty simply did not work on the device. Ktor's CIO
engine doesn't support server HTTPS at all; Jetty needs a Java-9 `ServiceLoader`
API that Android's runtime lacks. Three engines, three dead ends.

The fix was to stop fighting the framework and use the platform's own TLS stack:
Netty serves **plaintext on localhost**, and a tiny `javax.net.ssl.SSLServerSocket`
(Conscrypt — the same stack every Android app relies on) **terminates TLS on the
public port** and pipes the decrypted bytes to it. Rock-solid, and it kept the
exact same cert and pinning.

### Then a security audit found the real hole

An adversarial review turned up a **critical** flaw: the pairing deep link was
exported, so *any* installed app — with zero permissions — could fire
`lxconnect://pair?secret=…` and silently overwrite the server's bearer key with
an attacker-known value. The whole QR-pairing trust model, bypassed.

The fix: every pairing intent now requires an explicit in-app confirmation
dialog before the key is committed. Verified on device — a rogue intent no
longer changes anything; a user-confirmed pair still works. The same audit
tightened backup exclusion, config-file permissions, connection bounding,
constant-time secret comparisons, and input validation. All of it shipped in
**v1.0.1**.

## Automations: when a notification means something

Because the daemon already receives every phone notification over its SSE
stream, adding **trigger-based automations** was natural. A rule matches on
app / title / text (substring or regex) and fires actions — ring the phone,
reply, call any tool, or raise a desktop notification:

```json
{"rules": [{
  "name": "Boss on WhatsApp",
  "match": {"package": "whatsapp", "text": "boss"},
  "actions": [
    {"tool": "ring_device", "arguments": {"action": "start"}},
    {"notify": "{title}: {text}"}
  ]
}]}
```

There's also `lxconnect triage` to summarize your notifications grouped by app.

## Engineering hygiene

- **CI** on every pull request: the Android APK builds, the daemon's unit tests
  run (22 of them, covering the MCP client's handshake / id-correlation /
  timeouts and the automation rule engine), and the Nix flake is validated.
- **Signed releases**: a `v*` tag triggers a workflow that builds a signed APK
  and publishes a GitHub Release. v1.0 and the v1.0.1 security patch are out.
- **Reproducible**: the whole thing is a Nix flake — `nix develop` gives you the
  Android SDK, `nix run …#gui` launches the desktop client.

## Try it

```bash
# Pair your phone (shows a QR / deep link)
nix run github:olafkfreund/lxconnect -- pair

# Bridge notifications to your desktop
nix run github:olafkfreund/lxconnect -- daemon

# Or the GTK desktop client
nix run github:olafkfreund/lxconnect#gui
```

Grab the signed APK from the [releases page](https://github.com/olafkfreund/lxconnect/releases),
install it, grant notification + accessibility access, and pair.

## What's next

The bridge is feature-complete and released. The frontier is agent-native:
LLM-backed notification summarization, richer automation conditions, and
multi-device fleets. The foundation — a secure, pinned-TLS, tool-rich,
agent-controllable phone — is in place.

*Source: [github.com/olafkfreund/lxconnect](https://github.com/olafkfreund/lxconnect)*
