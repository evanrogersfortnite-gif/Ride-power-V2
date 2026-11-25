# RidePower (Kirbebike) BLE Protocol — Reverse-Engineered Spec

**App:** RidePower, package `com.wx.dfw`, v1.0.0 (versionCode 4)
**Stack:** Flutter (Dart 3.10.0 AOT) + `flutter_blue_plus`
**Target:** e-bike controller exposing a serial-over-BLE module (FFE0 service)

> Reconstructed by decompiling the app's `libapp.so` (Dart AOT snapshot) with
> blutter. All of the logic below comes from `power_ride/utils/bt_protocol.dart`,
> `power_ride/services/bluetooth_service.dart`, and `power_ride/services/query_controller.dart`.
> This is for interoperability with your own bike. Values marked *raw* have no
> confirmed engineering unit and should be sanity-checked against the official app.

---

## 1. GATT layout

| Role | UUID (16-bit) | UUID (128-bit) |
|------|---------------|----------------|
| Service | `FFE0` | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| **Write** characteristic (app → bike) | `FFE1` | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| **Notify** characteristic (bike → app) | `FFE2` | `0000ffe2-0000-1000-8000-00805f9b34fb` |

- The app scans, connects, discovers services, then looks for `FFE1` (write) and
  `FFE2` (notify). Log strings in the binary: *"找到写特征 FFE1"* ("found write
  characteristic FFE1"), *"未找到可写特征"* ("writable characteristic not found").
- Notifications are enabled on `FFE2` via the standard CCCD (`0x2902`).
- Writes go to `FFE1` with a **write request (with response)** by default.
  (Some FFE0-class modules only accept *write without response* — if writes fail,
  retry without response.)
- **No bonding, no pairing key, no encryption.** The only integrity check is an
  additive checksum (below). The binary contains no crypto on the protocol path.
- All frames are ≤ 20 bytes, so each fits in a single default-MTU (23) packet;
  no fragmentation is used by the app.

---

## 2. Frame format

Every frame — both directions — is:

```
[0x55]  [payload bytes...]  [checksum]
  ^byte0                       ^last byte
```

- **Header:** `0x55` (first byte of every valid frame).
- **Checksum:** `sum(all bytes except the last) & 0xFF` — a plain 8-bit additive
  sum over the header + payload. Appended as the final byte.
  (`BtProtocol.calculateChecksum` sums every element and masks `0xFF`;
  `BtProtocol.verifyChecksum` recomputes over `frame[0 .. len-2]` and compares to
  `frame[len-1]`.)

Frame **type is identified by length**, not by a type byte:

| Length | Meaning | Direction |
|--------|---------|-----------|
| 5 | Command / query | app → bike |
| 13 | Realtime data | bike → app |
| 20 | Parameter block | both directions |

All multi-byte numeric fields are **big-endian** (`high << 8 | low`).

---

## 3. Command / query frame (5 bytes, app → bike)

```
byte:   0     1       2     3     4
       0x55  CMD    0x00  0xA3  CHK
```

`CMD` (`SpecialRequestType` enum in `query_controller.dart`):

| CMD | Name | Effect | Bike replies with |
|-----|------|--------|-------------------|
| `0x00` | `none` | Poll realtime data | 13-byte realtime frame |
| `0x01` | `download` | Read current parameters | 20-byte parameter frame |
| `0x02` | `selfLearning` | Start motor self-learning (hall/phase) | realtime frames; `learnStatus` bit tracks progress |

Bytes 2 and 3 are fixed (`0x00`, `0xA3`). Ready-to-send frames:

| Purpose | Bytes |
|---------|-------|
| Poll realtime | `55 00 00 A3 F8` |
| Read parameters | `55 01 00 A3 F9` |
| Start self-learning | `55 02 00 A3 FA` |

The official app polls `CMD 0` on a timer to keep the live readout updated.

> ⚠️ **Self-learning spins the motor.** Only trigger it with the wheel off the
> ground and the bike safe to run, exactly as the official app warns
> (*"If the motor rotates in reverse during learning, please stop and restart."*).

---

## 4. Realtime data frame (13 bytes, bike → app)

Parsed by `BtProtocol.parseRealtimeDataFrame` (requires length ≥ 13, header `0x55`,
valid checksum).

```
byte:  0     1    2     3    4     5    6     7       8        9     10    11    12
      0x55  BvH  BvL  TvH  TvL  SpH  SpL  STAT  WORK   FAULT  CT    MT    CHK
```

| Field | Bytes | Decode | Notes |
|-------|-------|--------|-------|
| `batteryVoltage` | 1–2 | `((b1<<8)|b2) / 7.6222` | **Volts.** Divisor = `7.622222…` |
| `throttleVoltage` | 3–4 | `((b3<<8)|b4) * 5 / 1024` | **Volts** (0–5 V, 10-bit ADC) |
| `speed` | 5–6 | `(b5<<8)|b6` | **RPM** |
| `hallState` | 7 | `b7 & 0x07` | low 3 bits = hall sensor state |
| `learnStatus` | 7 | `(b7 >> 7) & 1` | 1 while self-learning active |
| `workState` | 8 | `b8` | **gear** 0–5 (official app shows "N Gear") |
| `fault` | 9 | `b9` | fault/error code (0 = OK) |
| `controllerTemperature` | 10 | `b10` | ℃ (raw byte) |
| `motorTemperature` | 11 | `b11` | ℃ (raw byte) |

Verified against the official app: it displayed **48.0 V** (⇒ raw 366 = `0x016E`),
throttle **2.5 V** (⇒ raw 512), speed **600 RPM**, hall **5**, "1 Gear".

---

## 5. Parameter frame (20 bytes, both directions)

- **Read:** send `55 01 00 A3 F9`; bike replies with this 20-byte frame
  (`BtProtocol.parseParameterFrame`, requires length 20, header `0x55`, valid checksum).
- **Write:** build this frame and send it to `FFE1`
  (`BtProtocol.createDownloadParametersFrame`). The bike applies the new settings.

Every parameter is a **single raw byte** (no scaling in the protocol layer).

```
byte:  0     1     2     3     4     5     6     7     8     9    10    11    12    13    14    15    16    17    18    19
      0x55  LCV   DCC   LM    ACC   P1    P2    P3    P4    P5    RGN   PHC   OV    UV    BST   SOT   ROT   CLT   RPP   CHK
```

Each byte is **1:1** with the official app's displayed value. The app constrains
every field to the **range/options below** (steppers and dropdowns), extracted from
the settings screen — these are the safe bounds; don't exceed them.

| # | Byte | Field | Control | Range / options (byte value) | Unit |
|---|------|-------|---------|------------------------------|------|
| 1 | LCV | `limitCurrentVoltage` | stepper | 0 – 10 | V |
| 2 | DCC | `dcCurrent` | stepper | 50 – 100 | % |
| 3 | LM  | `limitMode` | dropdown | 0=No Limit, 1/2/3=Limited 1/2/3 | — |
| 4 | ACC | `accelerateStrength` | dropdown | 1 – 5 (Level 1–5) | — |
| 5 | P1  | `pas1Speed` | stepper | 0 – 100 | % |
| 6 | P2  | `pas2Speed` | stepper | 0 – 100 | % |
| 7 | P3  | `pas3Speed` | stepper | 0 – 100 | % |
| 8 | P4  | `pas4Speed` | stepper | 0 – 100 | % |
| 9 | P5  | `pas5Speed` | stepper | 0 – 100 | % |
| 10 | RGN | `regenBraking` | dropdown | 0=Off, 1/2/3=Level 1–3 | — |
| 11 | PHC | `phaseCurrent` | stepper | 50 – 100 | % |
| 12 | OV  | `overVoltage` | stepper | 10 – 150 | V |
| 13 | UV  | `underVoltage` | stepper | 10 – 150 | V |
| 14 | BST | `boost` | toggle | 0=Off, 1=On | — |
| 15 | SOT | `stopOutputTemp` | stepper | 50 – 200 | ℃ |
| 16 | ROT | `recoverOutputTemp` | stepper | 50 – 200 | ℃ |
| 17 | CLT | `currentLimitingTemp` | stepper | 50 – 200 | ℃ |
| 18 | RPP | `rotorPolesPair` | stepper | 1 – 100 | — |

**Writing settings safely:** read the current 20-byte frame first (CMD 1), change
only the bytes you intend to, recompute the checksum, and send all 18 payload bytes
back. Don't send a frame with fields you haven't read — you'd overwrite them with
zeros.

Values are **1:1** with the official app (no scaling). The official app bounds each
field to the range/options in the table above; the companion apps here do the same,
so an out-of-range value cannot be entered. Still: **read first**, change one value
at a time, and keep over/under-voltage and current limits within your battery's and
controller's rated specs — the ranges above are the firmware's accepted bounds, not
necessarily safe for *your* pack.

---

## 6. Worked checksum example

Write PAS1 speed only (after reading current params). Say the read-back payload was
all zeros except you set `limitMode=1, pas1Speed=20 (0x14)`:

```
frame (no checksum): 55 00 00 01 00 14 00 00 00 00 00 00 00 00 00 00 00 00 00
sum                 = 0x55+0x01+0x14 = 0x6A
checksum            = 0x6A
full frame          : 55 00 00 01 00 14 00 00 00 00 00 00 00 00 00 00 00 00 00 6A
```

---

## 7. Connection flow (what the app does)

1. Scan for BLE devices advertising service `FFE0` (the bike module).
2. Connect, discover services.
3. Locate `FFE1` (write) and `FFE2` (notify) under service `FFE0`.
4. Enable notifications on `FFE2`.
5. Poll `55 00 00 A3 F8` on a timer → parse 13-byte realtime frames for the live display.
6. On entering settings: send `55 01 00 A3 F9` → parse the 20-byte parameter frame into the form.
7. On save: send the edited 20-byte parameter frame to `FFE1`.
8. Self-learning: send `55 02 00 A3 FA` and watch `learnStatus`.
