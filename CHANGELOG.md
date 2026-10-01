# Changelog

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
