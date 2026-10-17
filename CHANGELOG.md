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

## 1.1.0-beta.13 (2026-10-17)

### Added
- **Help tab: trusted support in one place.** Pick what's happening (followed, harassed in public, online abuse,
  violence at home, a child in trouble, medical, legal, someone to talk to) and get numbered steps with one-tap
  actions, plus ten verified government helplines (112, 181, 1098, 1930, NCW WhatsApp, 100, 1091, 108, 15100, 14416)
  with what happens when you call. Nothing is stored or sent.
- **Quick exit.** One tap hides Security4Her on the home screen and removes it from recent apps; it comes back when
  reopened.
- **Write it down.** A private complaint (what, when, where, who, witnesses, evidence) to share with the police or the
  NCW, or file online. Kept in memory only.
- **Kids mode for under-18s.** DigiLocker now shares the date of birth; anyone under 18 gets a children's section:
  SOS to their grown-ups, "I got there safely" check-ins, Childline 1098, "Is it OK?" safety lessons, a pretend call
  and Walk with me. Children can't post on the public map and their SOS never appears in map hotspots. The full app
  unlocks on their 18th birthday. The date of birth stays on the phone.

## 1.1.0-beta.12 (2026-10-16)

### Changed
- **New look: the Security4Her cat.** The app icon, the in-app logo, the notification icon, the watch app icon, the
  website and the iPhone port now use the cat from the install site instead of the old silhouette.

## 1.1.0-beta.11 (2026-10-02)

### Fixed
- **The phone's own Emergency SOS no longer starts with Pocket SOS.** Android's built-in SOS (and the double-press
  camera) only counts power presses that come within about half a second of each other. Pocket SOS now asks for a
  calm rhythm, about one press a second, and accepts presses up to 2 s apart. Tested on Android 15: three presses a
  second apart arm Pocket SOS every time and the system counts no fast taps at all; five fast presses still start
  the system SOS, as before.

## 1.1.0-beta.10 (2026-10-02)

### Fixed
- **Pressing power 3 times no longer runs into the phone's own Emergency SOS.** Pocket SOS used to wait 0.8 s after
  the third press to see if more were coming, so people kept pressing and reached the 5 presses that start Android's
  built-in Emergency SOS. It now arms on the third press with a long buzz, and any extra presses count as the same
  alarm. Me → Pocket SOS links straight to the phone's own Emergency SOS switch (tested on Android 15).
- Three presses were sometimes missed: Android can deliver the screen on/off events bunched together (70 ms apart
  for presses half a second apart), and those were taken for duplicates.
- After cancelling a false alarm, Pocket SOS ignored the power button for 10 seconds. It's ready again at once.

## 1.1.0-beta.9 (2026-10-02)

### Changed
- **Pocket SOS: press the power button 3 times** (about half a second apart, screen on or off). It replaces knocking
  as the default; knocking stays available as an option. It uses no motion sensor and no always-on wake lock, so it
  works on phones that pause sensors with the screen off and uses less battery. Presses during a phone call are
  ignored (the screen flips against her ear), and the test in Me → Pocket SOS reports how many presses were noticed.

## 1.1.0-beta.8 (2026-10-02)

### Security
- **Evidence opens only in Security4Her.** The copies in Downloads are now encrypted .s4he packages (AES-256-GCM,
  a fresh key for every hour of every SOS, protected by a 256-bit recovery secret kept in the Android Keystore).
  Galleries, file managers and other apps can't play them; the readable fingerprints.txt is gone. Copies saved by
  beta.7 are encrypted and the plain ones deleted automatically.
- **Recovery code and restore.** The evidence locker shows a recovery code (PIN-protected) and restores evidence
  from Downloads after a reinstall, offline. Tested: record, uninstall, reinstall, restore, all pieces intact.
- **SOS texts can't trip Android's SMS limit.** Android holds an app's texts behind an "allow?" prompt after about 30
  in 30 minutes. First alerts always go; repeat location updates are one SMS long and pause before the limit, while
  the live link keeps updating.

### Changed
- The police number is **100** (call buttons, the SOS screen and the default police text number).

### Removed
- The Siren.

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
