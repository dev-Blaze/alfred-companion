<div align="center">
  <img src="icon/icon.png" alt="Alfred icon" width="160" />
  <h1>Alfred for Wear OS</h1>
  <p><strong>Raise your wrist, speak, done — Task and Note capture for the <a href="https://github.com/dev-Blaze/alfred">Alfred</a> assistant.</strong></p>
</div>

The Wear OS companion to [Alfred](https://github.com/dev-Blaze/alfred). Open the app on your watch and it immediately starts listening in **Task** mode — speak, pause, and the transcript is relayed to your phone, which forwards it to your n8n webhook exactly like a capture made on the phone itself (same notifications, same history). A **Note** chip switches modes.

Built with Kotlin, Compose for Wear OS, and Hilt for a OnePlus Watch 4, but should behave on any Wear OS 3+ watch.

## How it works

- **Capture**: on-watch speech recognition with silence auto-stop — talking stops, sending starts. No taps needed after opening the app.
- **Relay, not direct send**: captures travel over the Bluetooth Data Layer to the phone app, which owns the webhook call. The watch needs no Wi-Fi and no webhook credentials.
- **Offline-safe**: out of phone range? The capture is queued on the watch ("Captured ✓") and syncs automatically when the phone reconnects.

## Requirements

- A Wear OS 3+ watch paired to an Android phone.
- The [Alfred phone app](https://github.com/dev-Blaze/alfred/releases) **v0.3.0 or newer** installed and configured on that phone (it receives the watch's captures).

> **Building from source?** The Data Layer only delivers between apps with the same application ID *and* signing certificate. Both this app and the phone app must be signed with the same keystore — debug builds of one won't talk to release builds of the other.

## Installing the APK on your watch

Watches have no USB port, so sideloading uses **wireless debugging**. One-time watch setup:

1. On the watch: **Settings → System → About → Versions** (or **Software info**) and tap **Build number** 7 times to unlock developer options.
2. **Settings → Developer options**: enable **ADB debugging** and **Wireless debugging**.
3. Make sure the watch and your computer are on the **same Wi-Fi network**.

Then, from your computer (needs [adb](https://developer.android.com/tools/releases/platform-tools)):

1. Download `alfred-companion-vX.Y.Z.apk` from [Releases](../../releases).
2. On the watch, open **Wireless debugging → Pair new device** — it shows a pairing code plus an `IP:PORT`.
3. Pair (one-time):
   ```bash
   adb pair 192.168.1.42:37099        # use the IP:PORT from the pairing screen
   # enter the 6-digit pairing code when prompted
   ```
4. Connect using the `IP:PORT` shown on the **main** Wireless debugging screen (the port differs from the pairing one):
   ```bash
   adb connect 192.168.1.42:41235
   ```
5. Install:
   ```bash
   adb install alfred-companion-vX.Y.Z.apk
   ```
6. Open **Alfred** from the watch's app list, grant the microphone permission, and speak your first task.

Tip: if `adb connect` stops working later, toggle Wireless debugging off/on on the watch and connect to the new port — the pairing itself survives.

## Development

Single-module Gradle project. `./gradlew :app:assembleDebug` builds; note that even debug builds are signed with the shared release keystore (`keystore.properties` + `keystore/`, both gitignored) so they can talk to the release-signed phone app — see the comment in `app/build.gradle.kts`.
