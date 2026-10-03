# Security4Her for iPhone

Native SwiftUI source port of the existing phone product, targeting **iOS 17+ / iPhone only**.
It uses the existing Supabase accounts, contacts, DigiLocker flow and tracking pages. Android and Wear OS
remain separate applications. There is no Apple Watch target and no Galaxy Watch bridge.

**Status:** source implementation, not an installed or device-validated release. Swift syntax parsing and
backend TypeScript compilation can run on Windows, but neither checks Apple SDK types. Xcode must build
the project and real iPhones must exercise its safety flows before anyone relies on this version.

## Open and build

On a Mac with a current Xcode installation supporting iOS 17 deployment:

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

The manual **iPhone build** GitHub Actions workflow runs Debug and Release simulator builds on macOS.
It does not sign, publish, install, deploy the backend or run tests. It was not triggered by this port.

The Xcode project is committed; no generator is needed to open it. After adding/removing Swift files,
run `python ios/generate_project.py` to regenerate source references. The generator also copies existing
brand artwork without changing Android assets. The included 180px icon supports development; supply all
required device sizes and a 1024px marketing icon before App Store submission.

## Backend deployment

The new migration and function must be deployed before live iPhone alerts and journeys work:

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
application does not claim exactly-once delivery. Old completed delivery records and journeys are pruned
after seven days. A cancellation cannot recall a message already accepted or in flight.

## Feature behavior

| Existing feature | iPhone implementation / boundary |
| --- | --- |
| Accounts, trusted contacts, DigiLocker | Existing backend; credentials in Keychain; saved profile/contacts available during temporary network loss. Backend verification is authoritative online. |
| SOS hold/countdown | 1.5-second hold, cancellable countdown, server incident, retrying Telegram alerts, explicit SMS/call controls. Countdown cancels if the app leaves the active state. |
| Automatic carrier SMS/call | Replaced by Apple's message composer and telephone confirmation. No silent SMS or silent emergency dialing. |
| Live SOS map | Existing token-based tracking page, location updates while iOS permits the session. Displayed fixes carry timestamps; stale data is not a live guarantee. |
| Standalone live sharing | Existing ntfy relay/page; one-hour session; stops publishing on cancellation. Already published data is not recalled. |
| Safe Journey | MapKit search/ETA, adjustable deadline, arrival/stillness/moving-away checks, local check-in notifications, durable server deadline. Practice mode never arms remote alerts. |
| Evidence | 15-second audio and 30-second foreground video segments, SHA-256 chain, account-filtered history, PIN/24-hour deletion rule and streamed ZIP export. One-hour recording limit. |
| Evidence after uninstall | App-private iPhone files are removed. Explicitly save exported ZIPs to Files or another destination before uninstall. Android's newer Downloads mirroring is not implemented here. |
| Fake call | In-app full-screen simulation, synthesized voice, immediate/scheduled invocation; notification entry when backgrounded. No fake VoIP/CallKit integration. |
| Siren | Generated audio at the user's output volume, optional steady torch. No forced volume change or strobe. |
| Pocket knock | Optional three-knock detector only while the app is active. App Shortcuts provide Siri, Back Tap and supported Action-button entry points. No global sensor daemon. |
| Maps/community | MapKit nearby search/directions; existing backend safe places and aggregated danger areas; Android-compatible public report feed, safe areas/places and confirmations. Public identities are not verified. |
| Themes/offline assistant/activity | Five theme selections, offline feature guidance and locally stored/exportable activity log. |
| Beta diagnostics | Manual local log export. Android's opt-in automatic beta telemetry collector is not ported. |
| APK installation/updater/watch tools | No iPhone equivalent; use Xcode, TestFlight or normal App Store distribution. |

Foreground video pauses when the application leaves the active state. Separate audio may continue with
the declared audio background mode, but calls, session interruptions, permission changes and force quit
can stop it. Missing permissions or interrupted recording are shown. Resuming recording requires an
explicit action or a valid camera session returning to the foreground; never claim background camera capture.

The hourly-key recovery/Telegram **evidence file** plan is deliberately not implemented. Telegram in this
port sends SOS **alerts**, not recordings. The current ZIP export is unencrypted, and the PIN is an in-app
control rather than an encryption password or uninstall lock.

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
