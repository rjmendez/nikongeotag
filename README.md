# NikonGeotag

A lightweight Android app that pushes **GPS location and time** to Nikon Z cameras over
Bluetooth Low Energy — the same "Connect to smart device" mode used by Nikon SnapBridge —
so your photos get geotagged without running the full SnapBridge app.

It pairs with a camera, keeps a low‑power foreground service running in the background, and
writes a fresh GPS fix (plus a one‑time clock sync) to the camera on an interval while the
camera is on, backing off automatically when it sleeps.

> **Personal, interoperability use only.** Use it only with a camera you own. "Nikon",
> "SnapBridge", and "Z" are trademarks of Nikon Corporation; this project is not affiliated
> with, endorsed by, or supported by Nikon.

## Features

- Pure‑Kotlin implementation of the Nikon LSS smart‑mode pairing handshake (Blowfish
  challenge/response) — **no proprietary vendor binaries**.
- Automatic camera discovery (scans for the live BLE advertisement / Nikon manufacturer data).
- GPS location write (`0x2007`, 41‑byte degrees + decimal‑minutes payload) and camera
  clock/time sync (`0x2006`).
- Material 3 dark UI with a live connection‑state card and last‑fix readout.
- Reliable background operation: foreground service, wake lock, auto‑reconnect on camera
  wake, restart on task‑swipe and device boot.

## Requirements

- Android 12 (API 31) or newer.
- A Nikon Z camera with SnapBridge "smart device" Bluetooth mode (developed/verified against
  the Z 7II; other Z bodies should work).

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # optimized, debug‑signed, sideloadable APK
```

Output: `app/build/outputs/apk/{debug,release}/`.

You'll need the Android SDK; set `sdk.dir` in a local `local.properties` (not committed).

## Install & use

1. Sideload the APK (enable "install unknown apps" for your installer).
2. Grant Location, Bluetooth, and Notification permissions; allow it to ignore battery
   optimizations when prompted.
3. On the camera: **Menu → Connect to smart device → Pairing → Start pairing**.
4. Tap **Pair camera** in the app, then confirm the Bluetooth passkey on the camera when
   prompted.
5. Sync starts automatically. Leave the camera on and the app running in the background —
   new photos will be geotagged.

## How it works

The Nikon smart‑mode BLE service (`0000de00‑3dd4‑4255‑8d62‑6dc7b9bd5561`) gates access behind
a 4‑stage Blowfish handshake over the PAIR characteristic (`0x2000`), followed by a Bluetooth
Classic bond. After that the camera accepts GPS (`0x2007`) and time (`0x2006`) writes. On
reconnect the app re‑runs the handshake with the saved controller identity.

## Credits

The reverse‑engineered protocol and crypto are based on prior open‑source work:

- [**hurui200320/nsg**](https://github.com/hurui200320/nsg) — Nikon Smart GPS (protocol docs
  and Kotlin reference the handshake/crypto here is derived from).
- [**gkoh/furble**](https://github.com/gkoh/furble) — original Nikon smart‑mode / Blowfish
  reverse engineering.

## License

Licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)), consistent
with the upstream furble project this derives from.
