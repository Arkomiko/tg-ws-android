# TG WS Proxy for Android

An Android port of [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). The protocol core is not rewritten: a real CPython runs inside the app and executes the very same `proxy/` package as the desktop version.

## How it works

```
Telegram for Android
        │  a regular MTProto proxy at 127.0.0.1:1443
        ▼
TG WS Proxy app ── foreground service ──┐
        │                                │  CPython inside (Chaquopy)
        │  obfuscated2 parsing, DC id,    │  running proxy/ unchanged
        │  re-encryption, packet split    │
        ▼                                │
WebSocket over TLS, port 443 ───────────┘
        ▼
kwsN.web.telegram.org → Telegram data center
```

This works because Android apps share one network namespace: one app's `127.0.0.1` is reachable from another. Telegram connects to the proxy exactly as it would to an MTProto proxy on the internet — no VPN, no root.

## Requirements

| | |
| --- | --- |
| Android | 7.0 and newer (API 24) |
| Architecture | arm64-v8a |
| Storage | about 60 MB installed |

**Why arm64 only.** Chaquopy ships the Python version we need (3.13, for the current `cryptography` wheels) for `arm64-v8a` and `x86_64` only. Supporting 32-bit ARM would mean rolling Python back several versions for little gain: arm64 has been standard since 2015, and Google Play has required 64-bit builds since 2019.

## Installation

Download the APK from the [releases page](https://github.com/Arkomiko/tg-ws-proxy-android/releases/latest) and install it. Android will warn about installing from an unknown source — the app is not published on Google Play.

## Connecting Telegram

**Automatically.** Tap "Start", then "Open in Telegram" — it will offer to enable the proxy.

**Manually.** Settings → Data and Storage → Proxy Settings → Add Proxy:

- Type: **MTProto**
- Server: `127.0.0.1`
- Port: `1443`
- Secret: the value shown on the app's main screen

## Quick settings tile

The app adds a tile next to Wi-Fi and the flashlight. A short tap toggles the proxy, a long press opens the app.

If the tile is missing, open the tile editor (pencil icon) and drag it in from the available list.

## Settings

Mirror the desktop settings window:

| Section | What it configures |
| --- | --- |
| Interface | language (Russian, English), theme (auto, light, dark) |
| MTProto connection | IP address, port, secret |
| Telegram data centers | DC number to IP mapping, one rule per line |
| Cloudflare Proxy | enable, custom domains |
| Cloudflare Worker | custom domains |
| Logs and performance | verbose log, buffer, pool size, max log size |
| Advanced | start after device boot, test data centers |

Ports below 1024 are rejected: Android does not allow binding them without root.

## Background operation

The proxy holds a listening socket and a pool of WebSocket connections, so it runs as a foreground service with an ongoing notification. The service type is `specialUse` rather than `dataSync`: the latter is capped at 6 hours per day on Android 15, which does not suit a proxy meant to stay up.

**Vendor ROMs may put the app to sleep.** Xiaomi, Huawei, Transsion (Infinix, Tecno) and others do this more aggressively than stock Android. The symptom: Telegram shows an endless "connecting to proxy", and everything resumes the moment you open the app.

What helps:

1. Remove restrictions in the ROM's power manager — `Phone Master`, "Battery management", "Autostart"
2. Lock the app's card in Recents
3. Accept the battery optimization prompt on first launch

On its side the app does what it can: holds a renewed partial wake lock, wakes itself with a watchdog alarm every 10 minutes, restarts after reboot and after being swiped from Recents, and — when the network changes or a VPN comes up — clears accumulated failures and rebuilds the connection pool.

## Logs

"Open logs" on the main screen shows the tail of the log and lets you copy it. The file lives in the app's private directory and is rotated.

A long press on the "Diagnostics" block dumps the full state — counters, blacklists, thread liveness — and writes it to the log. Useful when the proxy looks running but does nothing.

## Building from source

You need JDK 17, the Android SDK (platform 35, build-tools 35+) and Python 3.13 on the build machine — Chaquopy uses it to install dependencies.

```bash
cd android
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # release build
```

Release builds are signed with the key described in `android/keystore.properties`:

```properties
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

That file is not in the repository. Without it the release builds unsigned.

**The core is not copied into the module.** A Gradle task, `syncPythonCore`, copies `proxy/` and part of `utils/` from the repository root into `build/pythonCore`, and Chaquopy picks that folder up as a Python source directory. There stays exactly one source of truth for the protocol.

## Debugging in VS Code

`.vscode/launch.json` provides three configurations for the Run and Debug panel:

| Configuration | Action |
| --- | --- |
| Update to APP | build and install on the phone |
| Build APK | build only |
| Reload APP | restart the app on the phone |

JDK and SDK paths live in `.vscode/android.ps1` — adjust them for your machine.
