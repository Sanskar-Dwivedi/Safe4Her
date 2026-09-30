# Changelog

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
