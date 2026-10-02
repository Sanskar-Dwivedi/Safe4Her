# Watch shortcuts and wrist twist

Watch build: **1.1.0-beta.4 / version code 12**. Phone build: **1.1.0-beta.7 / version code 17**.
Update both APKs for the unlocked-phone fake-call fix. The existing `/s4h/fake-call/start` and
acknowledgement protocol is unchanged. Phone changes are limited to fake-call presentation/setup,
the required optional permission and the version bump.

## Set up after updating the watch

1. Opening **Security4Her Beta** now starts the five-second SOS countdown after setup. Cancel it to
   use settings or practice. The watch waits for saved setup state before choosing a screen, so the
   permission/setup page no longer flashes during loading.
2. In the watch's Quick launch/Knock Knock settings, choose **Security4Her** or **Security4Her SOS**.
   Both now start the countdown, including when Samsung resolves the selection to the default entry.
   Opening the app from our ongoing/resume notifications only shows status. Recreation and returning
   through a history launch do not retrigger SOS. Setting names vary by firmware.
3. For a fake call from the watch face, assign **Security4Her Fake Call** to the Home key's double press
   under Advanced features → Customize buttons. If Samsung's picker does not expose both launcher
   entries, use the tile or complication below. Picker compatibility needs checking on the actual watch.
4. Add the **Security4Her Beta** tile to the carousel for SOS and Fake call buttons. Or edit a watch face
   with a short-text or icon complication slot and select **Security4Her SOS** or **Security4Her Fake Call**.
5. Open **Practise wrist twist**. Hold your arm steady and rotate your wrist out, back, out, back.
   Select the firmest setting you can repeat comfortably. The chosen setting is saved immediately.
   Practice vibrates on recognition but never requests a call. Shake and voice SOS are paused while
   practice is active; leaving practice restores them. An intentional SOS shortcut is also ignored
   while practice is active. Existing incidents are never cancelled by practice.
6. Leave practice and turn **Fake call twist** on. Wait for **Ready — awake screen only**. You can return
   to the watch face. Raise your wrist to wake the display, then perform two twists. There is a
   30-second gesture cooldown. AOD/ambient mode is not an awake interactive screen.

The **Fake call now** button is the simplest way to distinguish a connection/phone issue from a
recognition issue. Opening the Fake Call shortcut requests the same action immediately.

## Phone setup for calls over other apps

On the phone, open Security4Her → Fake call → **Allow appear on top**. In Android's special-access
screen, select Security4Her and enable it, then return. The status refreshes when the app resumes.
Permission is optional and is never opened automatically by a watch request. With it, the receiver
attempts to open the existing fake-call activity directly over other apps. A notification remains
available if an OEM blocks the activity; it is cancelled when the call activity opens.

Without this access, an unlocked phone shows a heads-up notification even when full-screen alerts
are allowed. Full-screen intents normally launch on the locked/off screen. This is Android's
documented behavior, not a delivery failure. Locked-screen operation still depends on notification
and full-screen intent access. No overlay is drawn and no accessibility service is added.

Sources: [Android full-screen intent behavior](https://source.android.com/docs/core/permissions/fsi-limits),
[user-granted background activity launch exceptions](https://developer.android.com/guide/components/activities/secure-bal).

## What the feedback means

| Watch feedback | Meaning |
|---|---|
| Ready | The gesture service has received recent accelerometer and gyroscope samples |
| Raise wrist to wake | Sampling is paused while the screen is non-interactive |
| No sensor samples | Sensors registered, but fresh paired readings have stopped arriving |
| Open app / could not start | The saved ON preference is not proof that the service is running |
| Phone accepted | The phone acknowledged handling the request; Android may show a call notification instead of the full call screen |
| Phone not reachable | No request was successfully sent through the Wear Data Layer |
| No confirmation | The complete send-and-ack operation exceeded 12 seconds; check the phone before retrying |

The last request result stays visible for the current app process, with its time. Duplicate requests
within ten seconds are ignored to match the existing phone receiver. A successful phone ACK gives two
short vibrations; failure gives a long vibration. No fake calls are queued for later reconnection.

## Pinch on Galaxy Watch4

Settings → Accessibility → Interaction and dexterity → Universal gestures provides Samsung's
accessibility navigation. Default pinch moves focus; a fist selects. Follow Samsung's tutorial and
customise available mappings there. Watch buttons expose click actions and button semantics.

This is not an app-owned global pinch shortcut. Check whether your firmware allows Universal gestures
and Quick launch together before relying on both. The public Wear OS one-handed gesture API requires
Wear OS 7/API 37 and supported hardware; it does not provide this integration on the inspected
Watch4 with API 36/One UI 8. No hidden Samsung sensor or binder APIs are used.

Sources: [Samsung Universal Gestures](https://news.samsung.com/global/more-than-a-gesture-how-galaxy-watchs-universal-gestures-feature-enhances-accessibility),
[Wear OS one-handed gestures](https://developer.android.com/training/wearables/compose/one-handed-gestures).

## Connectivity and standalone SOS

Fake calls always require a reachable phone with the existing phone feature installed. Bluetooth is
one route; Wear Data Layer cloud connectivity can also work when available. Neither the new tile nor
the gesture turns the watch into an independent phone fake-call receiver.

Direct watch SOS uses the existing HTTPS backend and device link. The dashboard distinguishes an
unconfigured backend from a missing account link. This checkout has no configured backend credentials;
this update does not deploy or provision one. A configured URL/key, valid device token, deployed
`watch-sos` endpoint and watch internet connection are all required for direct delivery. Configuration
alone is not a delivery guarantee. See `local.properties.example` and `CONTRACT.md`.

## Implementation and remaining device checks

- `MainActivity`: consumes launch intents once; default and explicit SOS launches start the countdown.
  Status-only openings and recreation do not. The existing `knock` wire trigger is retained for compatibility.
- `TwistService`: separate, user-enabled foreground service of type `specialUse`; it needs no
  microphone or location permission. It listens while interactive, stops sensors during practice,
  cooldown or SOS, and reports actual sensor readiness. Boot/update recovery attempts to restart it;
  if Android refuses, opening the app resumes the saved preference. A force-stop still needs reopening.
- `WristTwistLogic`: integrates rotation along a 3D axis; four alternating strokes must meet the
  selected angle within 4.5 seconds, without excessive linear acceleration or pauses. Calibration is
  manual through three bounded settings, not automatic learning. Samples stay in memory.
- Tile and complication rendering never send a command; only explicit taps launch action activities.
- Foreground service type `specialUse` must be declared and justified for any future Play Store release.
  See [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Hardware recognition accuracy, daily battery cost, shortcut picker visibility, accessibility focus,
phone presentation with the new permission and reboot recovery remain unverified for this update.
No live SOS was triggered during implementation. Screen-off recognition is deliberately unsupported; no
partial wake lock is held. The public sensors found on this Watch4 are non-wake-up sensors.
