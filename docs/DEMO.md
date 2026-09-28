# Demo script and device test matrix

## Before the demo (T-30 min)
- [ ] Phone app is logged in and verified (mock). Contacts: Mom = teammate A's phone, Friend = teammate B's phone.
- [ ] **Test Mode ON.** Demo number = teammate A. Auto-call number = teammate A.
- [ ] Teammate B has linked Telegram (`/start <code>` to the bot) → ✓ next to the contact in the app.
- [ ] The watch home screen shows **Phone: connected** and **Account: <name>**, and Safety Mode is off.
- [ ] Knock Knock on the watch is set to open Security4Her. Try it once, then press **Cancel**.
- [ ] Safe places are seeded for **Nagpur** (`node scripts/seed_city.mjs --preset nagpur`, SQL run in Supabase). Pre-create 3 accounts if you want to show a danger zone appearing.
- [ ] Phone volume is up so the countdown notification is audible. The live-track page loads on a laptop.

## 5-minute demo
1. **The problem (30 s).** In danger, you can't unlock a phone and hunt for an SOS button.
2. **Gesture SOS (90 s).** Do Knock Knock on the watch. The countdown shows "SOS in 5… 4…".
   - Let it run. The watch shows **SOS ACTIVE → Phone ✓ received → SMS 3 sent → Server ✓**.
   - Teammate A's phone gets the SMS. Open the link on the laptop: the live map is moving.
   - After 10 s, the phone auto-calls the demo number and shows **Call 112**. Explain that Android
     forbids apps from auto-dialling emergency numbers.
3. **Works without the phone (60 s).** Turn Bluetooth off on the phone. The watch stays on Wi-Fi.
   Shake the wrist 3 times with Safety Mode on.
   - The watch shows **Phone ✗ not reachable, Server ✓ accepted, Telegram 1 alerted**.
   - Teammate B's Telegram gets the alert with the live link.
4. **Safety map (60 s).** Show the nearest police station and hospital, with **Go** opening walking
   navigation. Long-press to report a spot, then show a red zone where 3 women have reported.
5. **Women-only + privacy (30 s).** Show DigiLocker verification (mocked for the demo) and explain:
   - only gender is read;
   - the map shows a zone only after ≥3 reporters;
   - the live link dies when she taps **I'm safe**.

## Device test matrix (Test Mode ON; record pass/fail and time)

| # | Setup | Action | Expected |
|---|---|---|---|
| 1 | Watch + phone connected, **phone locked** | Knock Knock, wait 5 s | Watch: Phone ✓, SMS n sent, Server ✓. Teammate gets SMS with a working link. The page moves every 30 s. |
| 2 | Phone Bluetooth **off**, watch on Wi‑Fi | Safety Mode on, shake ×3 | Watch: Phone ✗, Server ✓, Telegram ≥1. Incident exists in Supabase `incidents` (`source = watch_direct`). |
| 3 | Phone off **and** watch Wi‑Fi off | Knock Knock | Watch: **NOT SENT – retrying**. Turn Wi‑Fi on → within ~1 min Server ✓. Exactly **one** row in `incidents`. |
| 4 | Any | Knock Knock, then **Cancel** within 5 s | Nothing sent; no incident row. |
| 5 | Watch off | Phone SOS button (hold) | SMS + server + countdown call. The watch (when on) shows the status. |
| 6 | After #1 | Wait 10 s after SMS | Auto-call goes to the demo number; the "Call 112" action is shown (**never tap it in tests**). |
| 7 | After #1 | Watch **I'm safe** (tap twice) | Phone ends the incident; "safe now" SMS arrives; the live page says the alert has ended; Telegram "marked safe". |
| 8 | Both paths up | Knock Knock | Exactly one incident row (the watch + phone upserts are deduplicated), and no duplicate SMS. |
| 9 | Map | Open Map near seeded area | Nearest 5 listed with distance; Call dials; Go opens navigation. |
| 10 | Map, 3 accounts | Each long-presses the same spot and reports | A red circle appears after the 3rd report (and not after the 1st or 2nd). |
| 11 | Unverified account | Try SOS / map | Blocked with "Verify your account…". |

If a check fails, note the watch status lines and `adb logcat -s S4hSos S4hPhoneLink S4hSafetyService S4hListener`.
