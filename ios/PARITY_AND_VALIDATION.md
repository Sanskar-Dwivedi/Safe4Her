# Android/iPhone parity and release checks

Implementation status: source changes complete except the deployment-specific standalone live-map URL.
Apple SDK builds and automated tests have passed in macOS CI. Physical-device safety flows, signing and
production deployment remain outstanding; this is not a release-ready certification.

| Feature | Android implementation | iPhone implementation and remaining boundary |
|---|---|---|
| Contact escalation | Carrier SMS and configured calls where permissions/platform rules allow; Telegram backend paths | Free Telegram queue; SMS composer and call confirmation require user action. iOS cannot silently send carrier SMS. |
| Journey monitoring | Phone foreground service, GPS and local timers | Server deadline continues independently of the phone. Route deviation, stationary detection and automatic arrival still depend on delivered GPS updates. |
| Pocket/voice/watch triggers | Foreground service triggers and Wear OS integration | Foreground knocks; Siri/Shortcuts, Back Tap, Action button, Lock Screen/Home widget and Control Center entry. No always-running sensor service or Wear OS bridge. |
| Background camera | Android service behavior depends on OS start/permission restrictions | Video pauses outside the foreground. Audio may continue under the permitted audio session but can be interrupted or killed. |
| External evidence | Existing Downloads mirroring/export | Opt-in Files-provider copies, per-segment and manifest hashes, retries and explicit cloud-sync uncertainty. No uninstall lock on either platform. |
| System check-in display | Android notifications/service UI | Live Activity with freshness and acknowledged deadline. No monitoring runs in the widget. |
| System check-in alarm | Existing Android reminders | Opt-in AlarmKit on iOS 26+, notification fallback on older systems or when unavailable. Alarm dismissal never means arrival. |
| Fake call | Android app/notification entry | In-app simulation entered through an explicit launch or notification. No fake incoming cellular/VoIP call. |
| Siren | Android volume controls and sound | User-controlled MPVolumeView and sound test; no programmatic forced system volume. |
| Maps | Existing Android map integrations | MapKit search, directions and ETA; shared backend/report feed. Both web-map URL formats now parse. |
| Distribution and diagnostics | APK install/update/watch tools and opt-in beta telemetry | Apple signing/TestFlight/App Store flow; local log export. Android distribution tools and automatic telemetry are not ported. |

The main iPhone-specific additions are Live Activities, AlarmKit reminders and native system controls.
They improve access and reminders; none bypasses suspension, force quit, denied permissions or networking.

## Automated validation

The [macOS and backend workflow for `cd7fcd0`](https://github.com/Sanskar-Dwivedi/Safe4Her/actions/runs/37144332715)
passed on 2026-10-03 UTC using Xcode 26.6. This revision includes the final camera queue and location
delegate isolation fixes; the subsequent documentation-only commit does not change the tested code.

- Debug and Release iPhone simulator builds, including the embedded widget extension.
- Unsigned Release iPhone device build. This checks Apple SDK compilation, not provisioning or installation.
- Seven hosted XCTest cases: four safety-policy regressions and three persisted-data/server-date compatibility tests.
- All migrations against a disposable PostgreSQL/PostGIS database, with **64 passing pgTAP assertions**.
- All **19 Node tests** for handlers and the standalone live viewer.

The seven simulator cases reported zero failures. Camera/location concurrency compiler warnings from the
earlier build are resolved. Camera hardware and rapid recording transitions still require the device checks below.

Additional local checks in the Windows checkout:

- 19 Node tests pass: existing handlers plus iOS authentication/readiness/deadline/lease/lifecycle cases
  and Android/iPhone live-map parsing, stale fix timestamps and ended events.
- TypeScript strict compilation passes for all Edge Functions.
- Three Android journey-recipient JUnit tests pass through `:mobile:testDebugUnitTest`; the mobile
  application and test Kotlin sources compile using JDK 17 and the installed Android SDK.
- Four Swift policy regression tests compile and pass with the Windows SDK: deadline extension,
  arrival quality/time separation, stale replies and resolved-incident restoration.
- Swift source syntax parsing passes. It does **not** type-check UIKit, WidgetKit, ActivityKit or AlarmKit.
- All nine AppIcon slots have correctly sized opaque PNGs. The marketing image is a resampling of the
  existing brand artwork. Project generation includes app, embedded widget and hosted XCTest targets.
- No backend was deployed, and no signed IPA or physical-device result was produced.

Xcode's App Intents training step emits `Could not archive SSU artifacts` while returning a successful
build. Its effect on Siri phrase discovery cannot be confirmed from the current codebase. Treat Siri
discovery and spoken activation as unverified until checked on a signed device build. Do not hide the
diagnostic by disabling App Intents metadata generation.

## Deployment order

1. Run migrations and pgTAP tests against a disposable staging database. Deploy the forward migration
   and matching `ios-safety` function together, pausing the dispatcher during the rollout.
2. Resume scheduled dispatch. Check heartbeat, bot configuration, HTTPS tracking URL and a consenting
   linked contact. Confirm retry after a temporary delivery failure and Test Mode suppression.
3. Deploy `web-track`, including `live-message.js`. Set `S4H_LIVE_URL` to the actual hosted `live.html` URL.
4. Keep the macOS workflow green: Debug/Release simulator compilation, unsigned device compilation and XCTest.
   Use Xcode 26+ so the AlarmKit branch is compiled, not omitted by `canImport`.
5. Complete the device checklist below before TestFlight/public distribution.

Cancellation terminal records and delivery event keys are retained for the account lifetime. A request
already delivered to Telegram cannot be recalled. Network ambiguity can still produce duplicates;
leases prevent concurrent workers from acknowledging one another's attempts, not exactly-once delivery.

## Physical-device checklist

Use iOS 17 and a current iOS 26+ device, with and without an Action button. Use consenting recipients.

- Start SOS from Siri, Back Tap, Action button, widget and Control Center while locked, unlocked,
  suspended and cold-launched. No countdown runs before activation; ordinary launch never starts SOS.
- Background/cancel during countdown. Verify the countdown stops and no remote incident is created.
- Cancel a start whose HTTP response is delayed or lost, restart the app, and retry. The server must
  remain terminal. Retry old extensions after a newer extension; the acknowledged deadline must not regress.
- Let a journey alert, resolve its SOS, then poll/relaunch. Neither the SOS nor the journey may reactivate.
- Test two arrival fixes, stationary arrival, inaccurate/approximate GPS, stale fixes and permission
  revocation. No automatic arrival outside the full accuracy circle; manual arrival remains available.
- Force quit, lose connectivity and reboot during an armed journey. The server should still escalate;
  verify the app never promises continuing local recording or GPS updates after termination.
- Disable notifications/Live Activities/alarm permission; use Focus and Silent mode. Confirm the displayed
  fallback. Dismiss alarms and Live Activities and confirm the server journey remains active.
- Change a deadline and cancel while alarm authorization is open. No stale alarm should remain scheduled.
- Record across a real phone call, audio-route change, app backgrounding, camera contention and media
  services reset. Check segment playback, retained partial files, gap notes and the one-hour recording cap.
  Repeatedly background/foreground and stop/restart recording while the camera is starting; configuration,
  start and stop share one serial queue, and late segment callbacks must not update a different segment.
- Select an external folder, revoke access, go offline, fill storage, change account and relaunch. Verify
  pending copies retry without deleting originals or copying another account's cases. Check hashes of
  completed copies and manifests, then independently verify provider/cloud upload on another device.
- Export and inspect a ZIP. Uninstall only after verifying an external copy exists; reinstall and confirm
  the external files survive while app-private files do not. The app PIN must never be represented as
  protecting external copies or blocking uninstall.
- Run all journey/SOS lifecycle events in Test Mode. No real trusted contact or emergency number may be
  contacted automatically. Android journey texts must use only the configured test number.
- Open Android and iPhone standalone share links, replay a stale GPS fix with a fresh relay timestamp,
  stop sharing offline, reconnect, and verify the viewer ends without declaring the person safe.

References: [Apple background execution limits](https://developer.apple.com/forums/thread/685525),
[SMS composer](https://developer.apple.com/documentation/messageui/mfmessagecomposeviewcontroller),
[AlarmKit](https://developer.apple.com/documentation/alarmkit/scheduling-an-alarm-with-alarmkit),
[system controls](https://developer.apple.com/documentation/widgetkit/creating-controls-to-perform-actions-across-the-system),
[Files directory access](https://developer.apple.com/documentation/uikit/providing-access-to-directories).
