# Ride-power-V2 — RidePower e-bike BLE interoperability

An independent Bluetooth-LE client for the **RidePower** e-bike app
(`com.wx.dfw`, by Kirbebike), built for interoperability with my own bike.
It talks directly to the bike's BLE controller: shows live battery voltage,
speed, throttle and temperatures, and reads/writes the controller settings
(speed-limit mode, PAS levels, current limits, etc.).

- **[`docs/PROTOCOL.md`](docs/PROTOCOL.md)** — full reverse-engineered protocol spec.
- **[`index.html`](index.html)** — single-file Web Bluetooth app (Chrome on Android).

## Protocol in one paragraph

GATT service **FFE0**, write characteristic **FFE1**, notify characteristic **FFE2**.
Every frame is `0x55` + payload + 1-byte checksum (`sum(previous bytes) & 0xFF`).
Frame type is identified by length: **5** = command, **13** = realtime data, **20** =
parameter block. No pairing key, no encryption. Commands: `55 00 00 A3 F8` poll
realtime, `55 01 00 A3 F9` read settings, `55 02 00 A3 FA` start self-learning.
Full field layouts and scaling (e.g. `batteryVoltage = raw/7.6222`) are in the spec.

## Running the web app

Web Bluetooth needs **Chrome on Android** and a **secure context** (`https://` or
`localhost`) — it won't run from a `file://` path, and it isn't supported by iOS
Safari or desktop Firefox.

**Easiest — GitHub Pages (HTTPS, nothing to install):**
1. Push this repo (done if you're reading it on GitHub).
2. Repo **Settings → Pages → Build and deployment → Deploy from a branch**, pick this
   branch and `/ (root)`, Save.
3. Open the published URL (e.g. `https://<you>.github.io/Ride-power-V2/`) in Chrome on
   your phone → **Connect** → pick your bike.

**Local alternative (with a computer):**
```bash
# serve over localhost (a secure context)
python3 -m http.server 8000
# then open http://localhost:8000 in Chrome
```
For another device on your network you'd need real HTTPS (e.g. `ngrok http 8000`).

### Using it
- **Connect** → choose your bike from the chooser (it's the device exposing FFE0).
- Live data starts polling automatically; current settings load once on connect.
- Change a field → **Write** (it reads back to confirm). **Read** re-pulls from the bike.
- **Self-learning** spins the motor — lift the rear wheel first.

## How it was reverse-engineered
The app is Flutter; all protocol logic is AOT-compiled into `libapp.so`. Steps:
`jadx` on the APK → identified Flutter + `flutter_blue_plus` → pulled `libapp.so` from
the `arm64-v8a` split → reconstructed the Dart classes with
[`blutter`](https://github.com/worawit/blutter) → read
`power_ride/utils/bt_protocol.dart` and `…/services/bluetooth_service.dart`.

## Safety / scope
For use with your own, road-legal, registered bike. Settings like current limits,
voltage cutoffs and speed-limit mode affect how the bike behaves and its road-legal
status — only change what you understand, keep within the official app's ranges, and
don't rely on this for safety-critical limits.
