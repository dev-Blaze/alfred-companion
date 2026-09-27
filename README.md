<div align="center">
  <img src="icon/icon.png" alt="Alfred icon" width="160" />
  <h1>Alfred for Wear OS</h1>
  <p><strong>Speak, review, send — Task and Note capture for the <a href="https://github.com/dev-Blaze/alfred">Alfred</a> assistant.</strong></p>
</div>

The Wear OS companion to [Alfred](https://github.com/dev-Blaze/alfred). First open starts listening in **Task** mode. Review or edit the transcript, then explicitly **Send** or **Cancel draft**. Later opens restore your draft and request history. Task and Note buttons select the capture type; Record starts another capture.

Built with Kotlin and Compose for Wear OS for a OnePlus Watch 4, targeting Wear OS 3+.

[0.3.1 release notes](RELEASE_NOTES_0.3.1.md). Calendar integration and functionality belong in n8n; the apps provide generic capture, clarification messages, and results, without implementing n8n integrations.

## How it works

- **Capture**: speech ends in an editable draft. Backgrounding retains partial speech without sending. Recording is bounded to 20 seconds plus a 3-second finalization deadline, including recognizer fallback.
- **Draft fixes**: changing Task/Note updates the saved draft type. Editing, cancelling, changing mode, and backgrounding invalidate late recognizer callbacks. Restored drafts are not replaced on open; permission is checked again before recording. Storage failures are shown before a send can proceed.
- **Relay, not direct send**: captures travel over the Bluetooth Data Layer to the phone app, which owns the webhook call. The watch needs no Wi-Fi of its own and no webhook credentials.
- **Durable drafts and requests**: private SharedPreferences retain capture ID, text, metadata, transfer state, and phone results across process death. Send persists the request before writing its DataItem. Locally queued means buffered by Play Services, not received by the phone. Interrupted or timed-out transfers show receipt unknown and offer Retry transfer with the same ID/path.
- **Phone results**: Phone received, Completed, and Needs attention distinguish acknowledgement from webhook completion. Phone-side `http_error`/`uncertain` outcomes must be reviewed/retried in phone History; repeating the capture transfer does not authorize another webhook execution.

> **On recognition and connectivity:** speech recognition is the one part that may need a network. The app prefers the on-device recognizer when the watch has one, but falls back to the default recognizer, which streams audio to a server. Tethered to your phone that's fine — Wear OS proxies the connection over Bluetooth. Fully out of range with no Wi-Fi *and* no on-device language model, recognition fails before there's a transcript to queue.

## Requirements

- A Wear OS 3+ watch paired to an Android phone.
- A matched phone build with durable capture acceptance and `/alfred/result/` support, configured with your webhook. Older releases without this protocol cannot report receipt/results.

> **Signing:** Data Layer requires the same application ID (`com.yshah.alfred`) and signing certificate on both devices. Use matched **release phone/watch builds** signed with the same private keystore for deployment. Debug uses normal Android debug signing; both debug builds must use the same debug certificate. A debug watch cannot communicate with a release phone. Release signing uses local gitignored `keystore.properties` and `keystore/`; without them release APKs are unsigned. Switching signing certificates requires uninstalling the previous build, which deletes its local drafts/history.

### The phone-side contract

The watch creates `requestId` when capture begins (or a typed draft is created). Explicit sends/retries write `/alfred/capture/{requestId}` with `requestId`, matching `sessionId`, `type` (`task`/`note`), `text`, `capturedAt` (epoch milliseconds), `timestamp` (legacy alias), `timeZone` (IANA), `schemaVersion` (integer 1), and `source` (`watch`). An incrementing `attempt` triggers a change event on retry without changing the immutable payload. Submitted requests cannot be edited under the same ID.

The phone durably deduplicates by request ID before deleting the capture DataItem. It publishes `/alfred/result/{requestId}` with `requestId`, `status`, `message` (up to 4,000 characters), and positive integer `resultRevision`: `accepted` means phone receipt or server acceptance, not completion; `success` means server-reported completion; `http_error` and `uncertain` need attention. The watch validates and persists results in a background listener, reconciling existing DataItems on foreground entry. Only newer revisions apply, allowing an explicit phone retry to supersede an earlier error; success is retained. Legacy results without revisions remain supported but cannot overwrite ordered results, regress success, or replace an error with a late acknowledgement. Result DataItems remain available for reconciliation.

The 0.3.1 phone accepts legacy capture fields `sessionId` and epoch-millisecond `timestamp`, defaulting timestamp-only captures to UTC. Modern metadata is preserved through webhook delivery. Older phones may accept transfers without providing durable receipt/results; use matched 0.3.1 builds for the full protocol.

The phone sends generic webhook metadata (`capturedAt`, `timeZone`, `source`, `requestId`, `conversationId`, `schemaVersion: 1`) alongside legacy `type`, `text`, `timestamp`, and `sessionId`. Webhook statuses distinguish `accepted`, `completed`, `failed`/`error`, and `needs_confirmation`; unknown outcomes need review. Legacy JSON/text/NDJSON remains readable, but HTTP 2xx or a legacy reply alone does not prove completion. Settings capability verification requires `{"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}`; legacy backends can still be saved as unverified. See the [phone webhook contract](https://github.com/dev-Blaze/alfred#the-webhook-contract).

Local storage has no automatic history deletion. Clearing app data or uninstalling loses drafts/history. A request interrupted before confirmed Data Layer buffering requires an explicit Retry transfer; already-buffered DataItems synchronize through Play Services. Retrying transfer does not prove whether the webhook executed. Do not recreate an uncertain request under a new ID to bypass phone deduplication.

Phone delivery survives process loss through Room/WorkManager. A timeout, connection failure, or interrupted in-flight send is uncertain and is not automatically replayed; phone History retry requires explicit acknowledgement of possible duplicate execution. Watch transfer retry only repeats the same immutable capture. Drafts/history on the watch, and credentials plus the delivery/history database on the phone, are excluded from backup/device transfer to avoid restoring queued mutations. History is not migrated by backup.

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
6. Open **Alfred**, grant microphone permission, speak, review the draft, and tap **Send**.

Tip: if `adb connect` stops working later, toggle Wireless debugging off/on on the watch and connect to the new port — the pairing itself survives.

## Development

Single-module Gradle project. Use Android Studio's JBR and the Android SDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint
./gradlew :app:assembleRelease
```

Device checks: interruption/screen sleep, process death, denied/revoked mic permission, native keyboard correction, round-screen scrolling with large text/TalkBack, recognizer finalization/fallback, offline buffering/reconnection, and matched-signature phone acknowledgements/results. JVM tests cover draft/send state, retries, callback invalidation, permission gating, persistence boundaries, and result validation; they do not emulate recognition or Play Services.
