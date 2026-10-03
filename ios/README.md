# Security4Her for iPhone

Native SwiftUI source port of the existing phone product, targeting **iOS 17+ / iPhone only**.
It uses the existing Supabase accounts, contacts, DigiLocker flow and tracking pages. Android and Wear OS
remain separate applications. There is no Apple Watch target and no Galaxy Watch bridge.

**Status:** Apple SDK builds and automated tests pass in macOS CI; this is not an installed or
device-validated release. Debug/Release simulator builds, the unsigned device app and widget, seven
XCTest cases, 64 database assertions and 19 handler/viewer tests passed. See
[the validation report](PARITY_AND_VALIDATION.md) for tested revisions and remaining diagnostics.
Real iPhones must exercise the safety flows before anyone relies on this version.

## Open and build

On a Mac with Xcode 26 or later (deployment remains iOS 17+):

1. Open `Config/Local.xcconfig` to configure this checkout.
2. Set the existing deployment's Supabase URL, **public** anon/publishable key, tracking page URL and live
   page URL. Never add a service-role key or Telegram token. Keep the existing slash escaping for URLs.
3. Open `Security4Her.xcodeproj`. Select the **Security4Her** scheme and an iPhone simulator or device.
4. For a real iPhone, select your Apple development team under Signing & Capabilities and use a bundle ID
   available to that team. Enable Developer Mode on the device if Xcode requests it.
5. Build and run. Without configuration, the entry screen offers an explicitly labelled offline demo.

Simulator builds disable signing and do not need an Apple account. Select a destination under iOS
Simulators and use Product > Run. Physical-device builds still need a valid team and provisioning profile.
Keep `DEVELOPMENT_TEAM = $(inherited)` in `Local.xcconfig` when choosing your team in Xcode; setting it
to an empty value overrides a team selected at the project level and causes a missing-team error.

Offline demo selection survives relaunch, including an unfinished practice SOS or journey. End it from
Home or Journey before signing out of the demo. Older builds' local practice sessions can also be restored
when no authenticated account is saved. Live, server-accepted or pending server sessions are not reset by
entering the demo; sign in to their original account and resolve them. Evidence is retained during recovery.

Command-line simulator build, from the repository root on macOS:

```sh
xcodebuild -project ios/Security4Her.xcodeproj -scheme Security4Her \
  -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/DerivedData CODE_SIGNING_ALLOWED=NO build
```

The **iPhone build** workflow runs on relevant pull requests and branch pushes, as well as
manual dispatch. It builds Debug and Release simulator apps, the widget extension, an unsigned Release
device app, and hosted XCTest regressions. A separate job runs handler/viewer tests and the migrations
with pgTAP in a disposable PostgreSQL/PostGIS database. It does not publish or deploy anything.

The Xcode project is committed; no generator is needed to open it. After adding/removing Swift files,
run `python ios/generate_project.py` to regenerate source references. All three targets and the test
scheme are generated; existing assets are preserved. App icons include every iPhone slot and an opaque
1024px marketing PNG. These are resampled from the existing brand icon, not newly drawn high-resolution
artwork; `ios/generate_icons.ps1` reproduces the asset sizes on Windows.

## Backend deployment

Deploy `20261003000006_ios_parity.sql` and the updated function before the iPhone update. Pause the
scheduled dispatcher during the migration/function rollout, then resume it and verify a fresh heartbeat.
The worker now acknowledges a specific lease; an old worker cannot finish a new lease. Do not leave
the old function running against the new migration. Use a staging project and the DB tests first:

```sh
supabase db push
supabase functions deploy ios-safety
```

Use the project's established deployment credentials. Required function secrets:

- Existing `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.
- Existing `TELEGRAM_BOT_TOKEN` and `TRACK_BASE_URL` (the hosted `web-track/index.html`).
- New `IOS_DISPATCH_SECRET`: an independently generated long random secret, only in the server and Vault.

`ios-safety` resolves and validates user JWTs itself; its gateway JWT check is disabled in `config.toml`.
It uses the caller's JWT for owner-checked client RPCs. Only scheduled dispatch uses service-role RPCs.
Test SOS incidents never enqueue Telegram messages. The iPhone SMS/call controls use only the configured
test number in Test Mode. Real Telegram delivery requires a verified account and linked contacts.

### Schedule durable alerts

Enable Supabase Cron (`pg_cron`), `pg_net` and Vault through the Dashboard. Store your project URL as the
Vault secret `s4h_ios_project_url`, and the same `IOS_DISPATCH_SECRET` value as `s4h_ios_dispatch_secret`.
Do not commit these values or put them into the app's xcconfig.

Run the following SQL in the project's SQL editor after those two Vault entries exist:

```sql
select cron.schedule(
  's4h-ios-dispatch', '* * * * *',
  $$select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets
            where name = 's4h_ios_project_url') || '/functions/v1/ios-safety',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-s4h-dispatch-secret', (select decrypted_secret from vault.decrypted_secrets
                              where name = 's4h_ios_dispatch_secret')
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 20000
  );$$
);
```

Monitor the Cron job and Edge Function errors. A successful scheduled invocation records a heartbeat.
Live journey setup refuses to arm if that heartbeat is more than three minutes old. This is a readiness
check, not an uptime guarantee: a later server outage can delay alerts. Deadlines are processed on the
minute, rather than exactly at the selected second. Telegram outages, rate limits and blocked bots can
also delay or prevent delivery.

SOS jobs are persisted before sending. Failed jobs retry, with two-minute leases and a 24-hour maximum
queue age; ended/expired incidents cancel pending jobs. Telegram acceptance is tracked independently
from confirmed human receipt. A network timeout after acceptance can cause a duplicate message; the
application does not claim exactly-once delivery. Terminal session identifiers and completed event keys
are retained until account deletion so delayed offline requests cannot recreate a cancelled session or
resend a lifecycle event. A cancellation cannot recall a message already accepted or in flight.
Journey start, arrival/end and SOS resolution messages use the same durable queue. Test sessions never
enqueue either alerts or lifecycle messages. The worker checks contact eligibility again when claiming.

## Feature behavior

| Existing feature | iPhone implementation / boundary |
| --- | --- |
| Accounts, trusted contacts, DigiLocker | Existing backend; credentials in Keychain; saved profile/contacts available during temporary network loss. Backend verification is authoritative online. |
| SOS hold/countdown | 1.5-second hold, cancellable countdown, server incident, retrying Telegram alerts, explicit SMS/call controls. Countdown cancels if the app leaves the active state. |
| Automatic carrier SMS/call | Replaced by Apple's message composer and telephone confirmation. No silent SMS or silent emergency dialing. |
| Live SOS map | Existing token-based tracking page, location updates while iOS permits the session. Displayed fixes carry timestamps; stale data is not a live guarantee. |
| Standalone live sharing | ntfy relay and the included `live.html`; supports Android `q` and Apple `ll` links, GPS measurement timestamps and explicit ended events. Pending stop events retry when the app can run. One-hour session; already published data is not recalled. |
| Safe Journey | Server-acknowledged deadlines, durable start/extend/end retries, cancellation tombstones and ordered revisions. +10 minutes extends the later of now or the existing deadline. Automatic arrival requires two fresh fixes at least 10 seconds apart, accuracy ≤50m, with the complete error circle within 150m. |
| Evidence | 15-second audio and 30-second foreground video segments, SHA-256 chain, account-filtered history, PIN/24-hour deletion rule and streamed ZIP export. One-hour recording limit. |
| Evidence after uninstall | Opt-in completed-segment and manifest copies to a user-selected Files/iCloud Drive folder. Copies are hash-verified and retried; originals remain local. External copies are outside the PIN. Provider/cloud upload is not confirmed by a successful file copy. |
| Fake call | In-app full-screen simulation, synthesized voice, immediate/scheduled invocation; notification entry when backgrounded. No fake VoIP/CallKit integration. |
| Siren | Generated audio, native user-controlled volume slider, two-second sound test and optional steady torch. No forced volume change or strobe. |
| Pocket knock and fast access | Foreground three-knock detection; Siri/Back Tap/Action-button shortcuts, Home/Lock Screen widget, and iOS 18+ Control Center button. All SOS entry points wait for the app to become active before counting down. |
| Journey system UI | Live Activity shows acknowledged deadline/status and last server confirmation. iOS 26+ opt-in AlarmKit reminders, with notification fallback. Dismissing any reminder/display does not check in or stop monitoring. |
| Maps/community | MapKit nearby search/directions; existing backend safe places and aggregated danger areas; Android-compatible public report feed, safe areas/places and confirmations. Public identities are not verified. |
| Themes/offline assistant/activity | Five theme selections, offline feature guidance and locally stored/exportable activity log. |
| Beta diagnostics | Manual local log export. Android's opt-in automatic beta telemetry collector is not ported. |
| APK installation/updater/watch tools | No iPhone equivalent; use Xcode, TestFlight or normal App Store distribution. |

Foreground video pauses when the application leaves the active state. Separate audio may continue with
the declared audio background mode, but calls, session interruptions, permission changes and force quit
can stop it. Audio interruption handling closes and retains the current segment, records gaps and retries
only when the interruption ends with permission to resume or the app returns to the foreground. Playable
unindexed media is recovered after termination with file-metadata timestamps; damaged containers remain
on disk and are not advertised as complete. Video still requires the foreground.
Camera configuration, recording requests and session shutdown use the same serial queue so rapid
foreground/background transitions cannot overlap session configuration with `startRunning`.

The hourly-key recovery/Telegram **evidence file** plan is deliberately not implemented. Telegram in this
port sends SOS **alerts**, not recordings. The current ZIP export is unencrypted, and the PIN is an in-app
control rather than an encryption password or uninstall lock.

### Standalone live-map configuration

`S4H_LIVE_URL` must be the HTTPS URL of the deployed **`web-track/live.html`**, without a topic parameter.
`TRACK_BASE_URL` on the server points to the separate incident map (`web-track/index.html`). The Pages
workflow deploys both files and `live-message.js`. Use its actual deployment output URL; do not assume
the Android app's older external map host contains these fixes. The app refuses to start standalone
sharing when this value is absent, rather than handing contacts an ntfy feed instead of a map.

### Evidence copy behavior

Choose the folder in Me → Evidence. This opts the signed-in account into copies of its existing cases
and newly completed segments. No folder is selected by default. Folder access is stored as an iOS
security-scoped bookmark and writes use file coordination. A revoked or stale bookmark, offline provider
or full disk leaves the originals intact and a visible pending status; retry or choose the folder again.
Account changes never enqueue another account's cases. Turning off copies stops queued work; a copy
already in progress may finish. Existing external copies are not deleted. Choose an external Files
provider or iCloud Drive, and verify the files on another device before relying on uninstall recovery.

### Regression commands

```sh
node --experimental-strip-types --test supabase/functions/tests/*.test.ts web-track/*.test.cjs
swift test --package-path ios
bash scripts/test_db.sh  # disposable local database only; requires PostGIS + pgTAP
```

On Windows, Node may need `--test-isolation=none`. The Windows Swift SDK used during implementation
does not contain XCTest. In a Visual Studio developer shell, compile `SafetyPolicy.swift` and
`Security4HerTests/SafetyPolicyTests.swift` together with `swiftc` to run the same four policy tests using
their assertion adapters. This does not validate any Apple framework integration.

## Release handoff

Build both configurations with the iOS SDK, exercise real iPhone permission/background/interruption
behavior, and run a controlled SOS/journey with consenting test recipients before release. Simulator
behavior cannot establish carrier SMS/call, camera, audio, GPS or locked-screen reliability.

Additional checks include: offline relaunch using cached setup; cancelling a timed-out journey request;
server retry after phone termination; false/failed delivery states; PIN lockout; account isolation;
partial camera files; ZIP import outside the app; restoring after a normal update; and confirmation that
normal app opening, viewing evidence and returning from verification never start an SOS.

The privacy manifest describes the current data flows, including Supabase identity/contact/location data.
It does not replace App Store Connect privacy answers. The deployed DigiLocker provider, privacy policy,
account-deletion process and review requirements must be reviewed before public distribution. This source
port does not assert App Store approval or an existing signed IPA.

Platform references:
[SMS composer](https://developer.apple.com/documentation/messageui/mfmessagecomposeviewcontroller),
[background camera interruption](https://developer.apple.com/documentation/avfoundation/avcapturesession/interruptionreason/videodevicenotavailableinbackground),
[Xcode requirements](https://developer.apple.com/xcode/system-requirements/),
[Supabase scheduled functions](https://supabase.com/docs/guides/functions/schedule-functions).
