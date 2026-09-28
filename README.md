# Security4Her

A women's safety system: a **Wear OS watch app** + an **Android phone app** + a **Supabase backend**.

A gesture on the watch (Samsung Knock Knock, or shaking the wrist in Safety Mode) starts a 5-second
cancellable SOS. The phone then sends an SMS with her location to selected family members and to the
police number, places a call after a countdown, and shares a live-tracking link. If the phone isn't
reachable, the watch reports the SOS straight to the server over Wi-Fi, and linked contacts get a
Telegram alert. The phone app also has a safety map with the nearest police stations, hospitals and
pharmacies, and community-reported danger zones. Login is women-only (DigiLocker verification;
a mock mode is available for the demo).

> **Hackathon build — not an emergency service.** It has not been tested for reliability, battery life
> or long-running use. Keep **Test Mode** on (the default): SOS messages and calls meant for police then
> go to your demo number, never to 112.

## Repository layout and ownership

| Path | What | Owner |
|---|---|---|
| `CONTRACT.md` | **The integration contract.** Read this first. | Claude |
| `wear/` | Wear OS watch app | Claude |
| `mobile/` | Android phone app | Codex (except `mobile/.../map/`, Claude) |
| `supabase/` | SQL migrations, pgTAP tests, Edge Functions | Claude |
| `web-track/` | Live-location page for family (GitHub Pages) | Claude |
| `scripts/` | DB test runner, city seed script | Claude |
| `docs/` | Plans, Codex brief, demo script | — |

## How it works

```
Knock Knock / shake ─► watch: 5 s countdown ─► incident UUID saved on watch
        ├─ Data Layer ─► phone ─► SMS (family + police/demo no.) ─► countdown call ─► live link
        │                  └────► Supabase upsert_incident (same UUID, deduplicated)
        └─ HTTPS (Wi-Fi) ─► Edge Function watch-sos ─► Supabase + Telegram alert to linked contacts
Family opens SMS link ─► web-track page ─► track(token) ─► live map (stops when she marks herself safe)
```

## Setup (about 30 minutes)

### 1. Supabase
1. Create a free project at supabase.com.
2. Under **Auth → Providers → Email**, turn **off** "Confirm email".
3. Push the schema and deploy the functions:
   ```bash
   npx supabase login
   npx supabase link --project-ref <your-project-ref>
   npx supabase db push                       # applies supabase/migrations/*
   npx supabase functions deploy watch-sos telegram-webhook digilocker-start digilocker-callback
   npx supabase secrets set TRACK_BASE_URL=https://<github-user>.github.io/Safe4Her/ \
       TELEGRAM_BOT_TOKEN=<from @BotFather> TELEGRAM_WEBHOOK_SECRET=<any long random string>
   ```
4. Point the Telegram bot at the webhook:
   ```bash
   curl "https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://<ref>.supabase.co/functions/v1/telegram-webhook&secret_token=<TELEGRAM_WEBHOOK_SECRET>"
   ```
5. Seed safe places for **Nagpur** (the demo city). Run this on your PC; it needs OpenStreetMap access.
   ```bash
   node scripts/seed_city.mjs --preset nagpur                    # writes supabase/seed/nagpur.sql (10 km around the centre)
   # any other city: node scripts/seed_city.mjs "Pune, India" --radius-km 8
   # optional curated hotspots: copy supabase/seed/known_hotspots.example.csv → known_hotspots.csv, then add
   #   --hotspots supabase/seed/known_hotspots.csv
   ```
   Paste the generated SQL into the Supabase SQL editor and run it. Places are updated by OSM id when
   re-run; hotspot rows are appended, so load each hotspot file only once.
6. DigiLocker stays in **mock** mode (`app_config.digilocker_mode = 'mock'`) until you have Requester
   credentials. To switch to the real flow:
   - set `DIGILOCKER_MODE=digilocker`, `DIGILOCKER_CLIENT_ID`, `DIGILOCKER_CLIENT_SECRET`,
     `DIGILOCKER_REDIRECT_URI` (`…/functions/v1/digilocker-callback`) and `DIGILOCKER_STATE_SECRET` as
     function secrets;
   - confirm the endpoint URLs in `supabase/functions/_shared/digilocker.ts` against your partner docs;
   - then `update app_config set value='digilocker' where key='digilocker_mode'`.

### 2. Live-tracking page (GitHub Pages)
1. **Settings → Pages → Source: GitHub Actions.**
2. **Settings → Secrets and variables → Actions → Variables:** add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
3. Merge to `main`. The `Deploy live-track page` workflow publishes `web-track/`.

### 3. Android apps
1. Copy `local.properties.example` to `local.properties` and fill in the SDK path, Supabase URL, anon key,
   `TRACK_BASE_URL` and `MAPS_API_KEY`.
   - The Maps key must be an Android key restricted to package `in.security4her.app` and SHA‑1
     `65:C4:9B:AA:AB:62:FC:17:2F:D2:D4:1F:10:87:21:C6:DA:8D:15:11`, which is the shared debug keystore in `keystore/`.
2. Build and install:
   ```bash
   ./gradlew :wear:assembleDebug :mobile:assembleDebug
   adb devices                                   # use -s <serial>: the watch often appears twice (IP + mDNS)
   adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
   adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
   ```
   Both apps **must** have the same package name and signing key, or the watch and phone can't talk.
   Installing over the old `com.safecircle.wear` demo is fine; it is a different app.
3. On the watch, open **Settings → Advanced features → Gestures → Knock Knock** and choose
   **Security4Her**. Menu names vary with watch software.
4. On the phone, sign up, verify (mock), grant all permissions, add contacts, and set Test Mode's
   **demo number** to a teammate's phone. The watch links automatically, showing **Account: <name>** on
   the watch home screen.

## Tests

| What | Command | Status |
|---|---|---|
| DB schema, RLS, RPCs (pgTAP, 39 tests) | `scripts/test_db.sh` (local Postgres 16 + PostGIS + pgTAP) | passing |
| Edge Functions (10 tests, mocked fetch) | `node --experimental-strip-types --test supabase/functions/tests/functions.test.ts` | passing |
| Edge Functions type-check | `npx -p typescript@5 tsc -p supabase/functions` | clean |
| Watch JVM tests (shake logic, contract wire format) | `./gradlew :wear:testDebugUnitTest` | 9 passing (compiled with kotlinc 2.3.21) |
| Live-track page | Chromium smoke test against a mock `track()` | passing |
| Android apps | `./gradlew :wear:assembleDebug :wear:lintDebug` | **not yet built.** The authoring environment couldn't reach Google Maven. Build on a PC first. |

The on-device test matrix is in [`docs/DEMO.md`](docs/DEMO.md).

## Known limitations (say these at the demo)
- **Android doesn't allow apps to auto-call 112.** After the countdown the phone calls your configured
  number and shows a one-tap **Call 112**. SMS to 112 isn't accepted in every state.
- **SMS and calls need the phone's SIM.** With no phone, the watch can only reach the server over
  Wi-Fi, which sends a Telegram alert to linked contacts. With no phone and no Wi-Fi, the watch shows
  **NOT SENT – retrying** and sends once a connection returns.
- **Knock Knock can't be told apart from tapping the app icon.** After setup, opening the watch app
  always starts the 5 s countdown.
- DigiLocker is mocked until Requester credentials exist.
- Danger zones are community reports plus curated data. They are **not** official crime statistics,
  and a cell appears only after 3 different women report it.
- Battery life, Doze behaviour, reboot recovery and long-running reliability are untested.

## Docs
- [`CONTRACT.md`](CONTRACT.md): package, versions, Data Layer paths, RPCs, SMS format.
- [`docs/HACKATHON_PLAN.md`](docs/HACKATHON_PLAN.md): the 15-hour two-builder plan.
- [`docs/CODEX_BRIEF.md`](docs/CODEX_BRIEF.md): the phone-app brief for Codex.
- [`docs/PLAN.md`](docs/PLAN.md): the full long-term roadmap and trade-offs.
