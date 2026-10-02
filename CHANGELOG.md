# Changelog

## iPhone source port (2026-10-03, unreleased)

- Add a separate native SwiftUI iPhone target (iOS 17+) with accounts/verification, trusted contacts,
  SOS, location sharing, journeys, maps/community, evidence, fake calls, siren, shortcuts and local help.
- Adapt SMS, calling, background capture and gestures to iOS restrictions with explicit status messages.
- Add owner-checked iPhone backend RPCs, durable Telegram alert jobs and server-monitored journey deadlines.
- Include an Xcode project, local configuration example, privacy manifest, deployment guide and manual
  macOS simulator-build workflow. No backend deployment or iPhone installation was performed.
- The encrypted recovery/Telegram evidence plan and Apple Watch app are excluded from this port.
- iOS SDK compilation, signing and real-device behavior remain unverified on the Windows development host.

## 1.1.0-beta.7 (2026-10-02)

### Security
- **New private signing key.** Earlier releases were signed with a key that is public in the source repo, so anyone
  could build a fake "update". Releases are now signed with a private key, rotated in with Android's signing lineage:
  installed copies update in place, and APKs signed with the old key are refused (tested on Android 15).
- **The safety PIN is stored as a salted hash**, never as digits, and locks for 30 seconds after 5 wrong tries,
  doubling each further miss.
- **The watch needs the PIN to end an SOS.** Before, "I'm safe" on the watch ended it without one; now the watch asks,
  and the phone checks it again before stopping anything.
- **The community map ignores junk:** malformed, oversized, future or month-old items, and more than 10 reports per
  author. SOS pins are shared rounded to the hour, and test SOSs are no longer shared at all.
- The watch refuses an update without a fingerprint. The website loads no third-party code and sends security headers.

### Added
- **Evidence survives the app being deleted.** Every recording and its fingerprint list are copied to
  Downloads/Security4Her evidence/, checked against their fingerprints.

### Changed
- **Evidence takes less space.** Video records at 700 kbps (was 1.2 Mbps) and audio at 24 kbps. After the 24-hour
  lock, the app keeps only the verified Downloads copy instead of two. Nothing already recorded is re-encoded, so
  every fingerprint still matches.
- **The map works on weak or no internet:** nearby places and map tiles are kept between visits, it opens at your last
  known position straight away, and it no longer redraws the whole screen while you drag it.

### Fixed
- A commit had broken the build (`FontWeights` import in DigiLockerDemo.kt).

## Watch build 12 / Phone build 17 (2026-10-02, local builds)

- Restore automatic five-second SOS countdown on normal watch opening after setup, covering Samsung
  Knock Knock launches that resolve to the default launcher. Status notifications remain dashboard-only.
- Fix the watch setup/location page briefly flashing before the saved setup state loads.
- Add optional **Appear on top** setup in the phone's Fake call screen. With this user-granted access,
  watch requests can launch the existing call activity over other apps. Preserve the call notification
  as a fallback; notification-only full-screen intents normally show a banner on an unlocked phone.
- Handle new requests delivered to an already-running fake-call activity.

This update requires both APKs and one-time phone permission setup. Phone code changes are restricted
to fake calls. On-device presentation remains to be verified.

## Watch 1.1.0-beta.3 — version code 11 (2026-10-02, local build)

### Changed
- Ordinary watch app opening shows the dashboard. Configure Knock Knock to the separate **Security4Her SOS** entry.
- Wrist twist uses all three gyroscope axes and measured rotation, with three saved strength settings,
  safe practice, live sample/readiness feedback and an explicit awake-screen requirement.
- Gesture monitoring has its own foreground service, independent of location/microphone permissions.
- Fake-call requests have a bounded send/ack deadline, duplicate-tap protection and a retained result.

### Added
- **Fake call now** button, **Security4Her Fake Call** launcher entry, SOS/fake-call tile and complications.
- In-app setup guidance for shortcuts and Samsung Universal Gestures; accessible button roles.
- Dashboard disclosure when direct watch SOS lacks backend configuration or an account link.

Phone code and protocol unchanged. Hardware checks remain pending; see [watch setup and limitations](docs/WATCH_GESTURES.md).

## 1.1.0-beta.6 (2026-10-01)

### Fixed
- Report a problem: the "How bad is it?" buttons were squeezed four to a row, splitting "Annoying" across two lines.
  They're now two to a row like the problem types, all the same size, with shorter labels that always fit.

## 1.1.0-beta.5 (2026-10-01)

### Added
- **Setup guide** (opens once after sign-up; Me → Setup guide): contacts, permissions, background running, safety
  PIN, a test text, Pocket SOS, the watch and the extras, one page at a time, each with a live check.
- **Fix texting in one tap.** Android 15 refuses SMS to apps installed from a download ("App was denied access").
  The app now reinstalls itself through Android's installer as an updater would, which lifts the block; then SMS can
  be allowed normally. Tested on Android 15.
- **Pocket SOS screen-off test** (Me → Pocket SOS): the real thing with the screen off, without an SOS, and a plain
  answer about what the phone felt (paused sensor, too soft, too slow).
- Help for phones whose own Quick Tap or Back tap opens Gemini when knocked.

### Changed
- Phone app updates install through Android's installer API, so updating never brings the SMS block back.
- Settings are folded into short rows that open one at a time.

### Fixed
- Pocket SOS went off from light taps: knocks taught gently could set it far too sensitive. Nothing is now more
  sensitive than Light, and taps on a phone lying still on a table need to be much harder (unless the proximity
  sensor says it's in a pocket or bag).
- Pocket SOS missed knocks on phones that wake the screen on a tap, and on phones that pause ordinary sensors with
  the screen off.
- The manual steps for allowing SMS were in an order Android doesn't allow.

## 1.1.0-beta.4 (2026-10-01)

### Added
- **Beta testing** and **Privacy** pages on the website, linked from the app's About section and the site footer.

### Fixed
- The Privacy link in About opened a page testers couldn't see.

## 1.1.0-beta.3 (2026-10-01)

### Added
- **About** in the phone app (Me → About) and on the website: the team behind Security4Her — Atharva Lakhe,
  Sanskar Dwivedi, Mohit Hadap and Ashay Malode — with links to the website and privacy notice.

## 1.1.0-beta.1 (2026-10-01)

Public beta for testing before a commercial launch.

### Added
- **Beta agreement** on first launch (phone and watch): testers choose which anonymous test data to share, and can
  change it or delete everything they sent at any time.
- **Test data, sorted into categories:** crashes (with stack traces), errors, security events, false alarms, feature
  use, speed and device model. Numbers, places, links and names are removed on the phone first; location, contacts,
  messages and recordings are never collected.
- **Report a problem** (Me → Beta testing), with an option to attach recent errors.
- **Private beta database** (`supabase/beta/beta_testing.sql`) that rejects anything a tester didn't agree to, limits
  each tester to 600 events an hour, and groups everything into a ranked list of problems for the team.

### Changed
- Both apps are named **Security4Her Beta**.

## 1.0.0 (2026-09-30)

### Added
- **Fake call** (phone): a realistic incoming call from Mom, Dad or any name, now or in 10 or 30 seconds.
  Answering starts a call timer and a voice on the other end.
- **Siren** (phone): a loud alarm that plays even on silent, with a flashing screen and a strobing torch.
- **Automatic watch updates**: the watch checks, downloads and installs new versions by itself, silently,
  never during an SOS, and only while charging when Voice SOS or Safety Mode is on.
- **Protection survives restarts**: after the watch restarts or updates, Voice SOS and Safety Mode come back
  on their own, or with one tap on a *Tap to resume* notification.
- **Voice SOS switch on the watch home screen**, with live status and a way into practice.

### Changed
- Installing the watch app from the phone starts as soon as the 6-digit code is typed, and the one-time
  watch settings fold away once the watch is found.
- Release builds are optimised with R8: the phone app is 11 MB (was 27 MB) and the watch app 53 MB (was 67 MB).

### Fixed
- The safety map no longer lists places from another city when nothing is nearby.
- The watch no longer shows "Voice SOS on" after a restart when it isn't actually listening.

## 0.4.1

- Voice SOS is more sensitive: longer pauses, quieter voices and "help me" count, with no new false
  triggers on 276 test recordings.

## 0.4.0

- **Voice SOS** on the watch: say "HELP HELP HELP" to start the SOS countdown, recognised entirely on the watch.
- Install and update the watch app from the phone over Wi-Fi, no computer needed.

## 0.3.x

- In-app updates for the phone and watch, verified by SHA-256.
- Pocket SOS: knock three times through a pocket or bag with the screen off; learns her knock.
- Offline in-app assistant, five colour themes, Save confirmation for SOS settings.
