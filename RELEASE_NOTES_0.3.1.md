# Alfred Companion 0.3.1

## Draft and delivery fixes

- Speech ends in a durable, editable draft; only Send authorizes transfer. Backgrounding retains partial text. Restored drafts/history prevent capture-on-open from replacing existing work.
- Task/Note selection updates the saved draft type. Editing, cancellation, mode changes, and lifecycle interruption invalidate late recognizer callbacks. Recording rechecks microphone permission, supports fallback, and has bounded capture/finalization deadlines.
- Storage must commit before transfer. Submitted payloads are immutable; retries retain the request ID/path and increment `attempt`. Local buffering is distinguished from phone receipt, completion, and receipt unknown.
- Background result persistence and foreground reconciliation now use `resultRevision`. Only newer revisions apply; confirmed success is retained. Legacy unversioned results cannot overwrite ordered results or regress a terminal result to acknowledgement.

## Phone compatibility and security

- Use the matched 0.3.1 phone for durable Room/WorkManager acceptance, deduplication, recovery, and results. Legacy capture `sessionId`/`timestamp` remains accepted by the phone, with UTC for timestamp-only captures.
- Results use `accepted`, `success`, `http_error`, or `uncertain`. Only server-reported completion becomes `success`. Watch transfer retry does not authorize webhook re-execution; uncertain/failed delivery requires review and an explicit phone History retry acknowledging possible duplicates.
- Webhook metadata adds capture time, timezone, source, request/conversation IDs, and schema version while retaining legacy fields. Responses distinguish `accepted`, `completed`, `failed`/`error`, and `needs_confirmation`; legacy JSON/text/NDJSON still works without proving completion. Empty 2xx means accepted.
- Settings capability verification requires `{"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}`. Legacy backends remain saveable as unverified. The phone enforces HTTPS/auth validation, disables redirects/automatic connection retries, and bounds responses to 1 MiB. The watch stores no webhook credentials.
- Watch drafts/history are excluded from backup/device transfer. The phone excludes credentials and its entire delivery/history database, including journals; **history is excluded too**, preventing restored mutations from replaying.

Calendar integration and functionality belong in n8n. The apps provide generic capture, clarification messages, and results; no calendar or other n8n integration is implemented. See [README](README.md#the-phone-side-contract) for protocol details.

## Validation and deployment

- Passed in both repositories: `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleRelease` (including up-to-date Gradle tasks).
- Device migration was not executed; 0.3.1 is not claimed as device-verified. Recognition, reconnection, and actual phone/watch delivery still require device checks.
- Both APKs must use application ID `com.yshah.alfred` and the same signing certificate. Deploy matching release builds from the same private keystore, or debug builds sharing a debug certificate. Switching certificates requires uninstalling and deletes local drafts/history; backup does not migrate them.
