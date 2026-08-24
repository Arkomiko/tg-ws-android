# Building from source

## Console proxy

The core is a plain Python package and runs on any desktop system without Android:

```bash
pip install -e .
tg-ws-proxy --port 1443
```

Command-line options are listed in `proxy/tg_ws_proxy.py`, function `main`.

## Android application

Building the APK is covered separately — [README.android.md](./README.android.md).

## Core tests

```bash
pip install pytest
pytest tests -q
```

## Desktop tray application

This fork does not ship one: the repository contains only the core and the Android app.
Windows, macOS and Linux builds live in the original project —
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
