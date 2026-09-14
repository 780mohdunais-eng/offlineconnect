# OfflineConnect — MVP (Phases 1–3)

Offline, no-internet, Bluetooth-only phone-to-phone communicator. This build is the
**MVP slice**, per the phased plan in the original spec: discovery → connection →
text chat. Voice, relay/mesh, encryption, and file sharing are **not implemented
yet** — see "What's not built yet" below. Nothing in the UI pretends otherwise;
there are no voice or relay buttons in this build.

## What actually works right now

- Bluetooth device discovery (classic scan + previously-paired devices)
- RFCOMM connect/accept between two phones, both directions
- Real-time text chat over the socket, with delivery status and timestamps
- Chat history saved locally per device (DataStore), survives app restart
- Auto-reconnect with backoff if the connection drops
- Runtime permission handling for BLUETOOTH_SCAN/CONNECT (API 31+) and
  BLUETOOTH/BLUETOOTH_ADMIN (older)
- Graceful handling of: Bluetooth off, permission denied, connect timeout,
  socket errors, corrupted packets (dropped, not crashed)

## What's not built yet (by design, per the phased plan)

| Phase | Feature | Status |
|---|---|---|
| 4 | Voice messages (record/send/play) | Not started |
| 5 | Real-time Push-to-Talk voice | Not started |
| 7 | Relay/Mesh mode | Not started (packet format has `ttl`/`hopCount`/`destinationId` fields reserved for it) |
| 8 | Transport/app-level encryption | Not started |
| 14 | File sharing | Not started |

The packet protocol (`Packet.kt`) already includes the fields those phases need
(sequence numbers, TTL, hop count, message types like `VOICE_DATA`) so adding them
later doesn't require changing the wire format — but nothing sends those message
types yet.

## Build instructions

1. Open this folder in **Android Studio** (Koala/2024.1 or newer recommended).
2. Let Gradle sync — it needs internet access the first time to pull dependencies
   (AndroidX, Compose, kotlinx-serialization). This was built in a sandbox with no
   internet access, so **it has not been compiled or run here** — you'll get the
   first real build signal when you sync in Studio.
3. Connect two physical Android phones (API 26+) via USB or run on one physical +
   one emulator for UI testing only — **Bluetooth does not work in the emulator**,
   so real device-to-device testing needs two physical phones.
4. Build → Run on both devices (`Run 'app'`, pick a different device each time).

## Installing on two phones

1. Enable "Install from unknown sources" if sideloading the APK directly, or run
   directly from Android Studio via USB debugging.
2. Enable Bluetooth on both phones.
3. Open OfflineConnect on both. Grant the Bluetooth permission prompt on each.

## Using it

- **Set your name**: tap your name in the top bar of the device list.
- **Find the other phone**: tap "Scan Nearby Devices" — the other phone must have
  the app open (it's listening for incoming connections as soon as it launches).
- **Connect**: tap "Connect" next to their name. Either phone can initiate.
- **Chat**: once connected you're dropped into the chat screen automatically.
  Type and send — messages appear on the other phone in real time over Bluetooth,
  no internet involved at any point.
- If the phones move out of range, you'll see "Connection lost — retrying…" and
  it will auto-reconnect when they're back in range (bounded retry, not infinite).

## Realistic Bluetooth range

This app does **not** claim extended or unlimited range. Actual range depends on
your phones' Bluetooth hardware, Bluetooth version, antenna, obstacles, and
orientation — typically ~10 m (30 ft) for Classic Bluetooth on phones, less
indoors/through walls. Relay mode (Phase 7, not yet built) is the planned way to
extend effective range using a third phone as a hop — it will never be labeled as
a direct connection when it isn't.

## Android version compatibility

- `minSdk = 26` (Android 8.0), `targetSdk = 35`.
- The spec asked for "8.0+ where reasonably possible" — 8.0 *is* API 26, so this
  hits that floor exactly. Going lower (API 21–25) would mean supporting the old
  runtime-permission model and older `BluetoothSocket` quirks for very little
  practical benefit today.

## Known limitations of this MVP

- Two devices only — no multi-device chat yet (that arrives with relay mode).
- No message encryption yet — don't use this for sensitive communication in its
  current state.
- Discovery relies on Android's classic Bluetooth discovery, which can be slow
  (10–12s) and inconsistent across OEM Bluetooth stacks (Samsung/Xiaomi/etc. all
  behave slightly differently) — this is an Android platform limitation, not
  something fixable purely in-app.
- Not yet tested on physical hardware (no internet/devices available in the
  sandbox this was built in) — treat this as a solid starting point to debug from
  in Android Studio, not a guaranteed first-run success.
