# Device test checklist

Run this on a real phone (and watch) before every release. Keep **Test Mode** on (Me → Test Mode) with a
test number set to a phone you control, so police texts and the automatic call never reach 112.

## Setup

- [ ] Phone app on the version being released (Me → App updates → Check).
- [ ] Signed in and verified with DigiLocker.
- [ ] At least one trusted contact whose phone you can see.
- [ ] Test Mode on, test number saved (green *Saved* tick).
- [ ] SMS allowed (Home shows no red "SOS texts aren't automatic yet" card).
- [ ] Pocket SOS on and taught (Me → Pocket SOS → Practise and adjust strength); full-screen alerts allowed.
- [ ] Watch app installed from the phone (Me → Install or update the watch app) and showing *Phone connected*.

## Checks

Note pass or fail and the time taken for each.

| # | Setup | Action | Expected |
|---|---|---|---|
| 1 | Phone unlocked | Hold SOS for 1.5 s | SMS with a Maps link and a live link arrives; recording starts; the call follows after the countdown |
| 2 | Mobile data off | Hold SOS | SMS still arrives; the live page catches up when data returns |
| 3 | Phone locked, Pocket SOS on | Knock 3 times through a pocket | Strong vibration, countdown over the lock screen, SOS after 5 s |
| 4 | As #3 | Knock 3 times, then tap Cancel | Nothing is sent; the log says it was cancelled |
| 5 | Pocket SOS on | Walk for a minute, sit down, put the phone on a table | No false trigger |
| 6 | SOS active, PIN set | Tap I'm safe and enter the PIN | SOS ends; contacts get an "I'm safe" text; the live page says she is safe |
| 7 | Safe Journey running | Ignore the check-in | SOS starts with the reason in the text |
| 8 | Watch linked | Hold SOS on the watch | The phone runs the SOS; the watch shows the delivery status |
| 9 | Watch, Voice SOS on | Say "HELP! HELP! HELP!" | Watch vibrates and counts down 5 s, then the SOS runs |
| 10 | Watch, Voice SOS on | Normal conversation, TV, "help desk", "hello hello hello" for 5 minutes | No countdown |
| 11 | Watch, Voice SOS on | Restart the watch | A *Tap to resume* notification; one tap and it listens again |
| 12 | Older watch version installed | Wait, or open the app | The watch updates itself; while Voice SOS is on it waits until it's charging |
| 13 | New watch, Wireless debugging on | Phone: Me → Install or update the watch app, type the code | Four ticks in about a minute; the app opens on the watch |
| 14 | Home → Fake call | Ring now, then Answer | A realistic ringing screen; after answering a timer runs and a voice speaks |
| 15 | Phone on silent | Home → Siren | Loud alarm, flashing screen and torch; one tap stops everything and restores the volume |
| 16 | After an SOS | Me → Evidence locker → case → Check | "Intact"; export makes a zip with a manifest |
| 17 | Older phone version installed | Open the app | *Update to …* card; the update installs over the old version |

## If something fails

Open Me → Activity log and share it. For the watch:

```bash
adb logcat -s S4hSos S4hPhoneLink S4hSafetyService S4hVoice S4hUpdate S4hRearm
```
