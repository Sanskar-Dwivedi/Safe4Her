# Demo script and device checklist

## Before the demo (30 minutes ahead)

- [ ] Phone app on the latest version (Me → App updates → Check).
- [ ] Signed in and verified with the demo DigiLocker account (Aadhaar `2345 6789 4821`, OTP `123456`).
- [ ] Trusted contacts: at least one teammate's phone.
- [ ] **Test Mode on**, demo number set to a teammate's phone, and saved (green *Saved* tick).
- [ ] SMS allowed for the app (Home shows no red "SOS texts aren't automatic yet" card).
- [ ] Pocket SOS on, knock taught (Me → Pocket SOS → Practise and adjust strength), full-screen alerts allowed.
- [ ] Watch app installed and showing *Phone linked* (optional).
- [ ] A laptop ready to open the live-map link from the SOS text.
- [ ] Phone volume and vibration on.

## 5-minute demo

1. **The problem (30 s).** In danger there's no time to unlock a phone, the network may drop, and afterwards there's no proof.
2. **One press (90 s).** Hold SOS on Home.
   - The SOS screen shows the running timer, *Recording evidence* and the live link.
   - The teammate's phone receives the SMS; open the link on the laptop and show the map moving.
   - After the countdown the phone calls the demo number and shows *Call 112*. Android does not let apps dial 112 by themselves.
3. **Hands-free (60 s).** Lock the phone, put it in a pocket and knock three times.
   - It vibrates strongly, wakes over the lock screen and counts down 5 seconds. Tap *I'm okay, cancel* to show a false alarm is easy to stop.
4. **Safe Journey and the map (60 s).** Start a Safe Journey to a nearby destination; show the safety map with safe places and red zones.
5. **Trust and proof (30 s).** Open the evidence locker and run the integrity check; show the women-only DigiLocker step.

## Device checklist (Test Mode on; note pass/fail and time)

| # | Setup | Action | Expected |
|---|---|---|---|
| 1 | Phone unlocked, SMS allowed | Hold SOS 1.5 s | SMS with Maps link and live link arrives; recording starts; call after the countdown |
| 2 | Mobile data off | Hold SOS | SMS still arrives; live page catches up when data returns |
| 3 | Phone locked, Pocket SOS on | Knock ×3 through a pocket | Strong vibration, countdown over the lock screen, SOS after 5 s |
| 4 | As #3 | Knock ×3, tap Cancel | Nothing is sent; log says "cancelled before it started" |
| 5 | Pocket SOS on | Walk for a minute, sit down, put the phone on a table | No false trigger |
| 6 | SOS active, PIN set | Tap I'm safe, enter PIN | SOS ends; contacts get an "I'm safe" text; live page says she is safe |
| 7 | Safe Journey running (Test Mode timers) | Ignore the check-in | SOS starts with the reason in the text |
| 8 | Watch linked | SOS on the watch | Phone runs the SOS; watch shows the delivery status |
| 9 | After an SOS | Me → Evidence locker → case → Check | "Intact"; export produces a zip with a manifest |
| 10 | Older version installed | Open the app | *Update to …* card; update installs over the old version |

If something fails, open Me → Activity log and share it; for the watch, `adb logcat -s S4hSos S4hPhoneLink S4hSafetyService`.
