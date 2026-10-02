# Beta testing guide (for the team)

Security4Her **1.1.0-beta.1** is the build for the college beta test. Testers agree, on first launch, to share
anonymous test data in the categories they choose. This guide covers turning collection on, reading the data, and
working through problems.

## 1. Turn collection on (once, about 5 minutes)

1. Create a free project at [supabase.com](https://supabase.com) (region: Mumbai, `ap-south-1`).
2. Open **SQL Editor → New query**, paste all of [`supabase/beta/beta_testing.sql`](../supabase/beta/beta_testing.sql)
   and click **Run**. It is safe to run again.
3. Open **Project Settings → API** and copy the **Project URL** and the **anon public** key.
4. Add them to `web-install/version.json` and redeploy the site:

   ```json
   "beta": { "url": "https://YOUR-PROJECT.supabase.co", "key": "eyJ…anon key…" }
   ```

   `publish.sh` keeps this entry on every release. The apps read it within 6 hours (or on the next launch) and upload
   everything they queued, so no new app build is needed.

The anon key is public by design. With it, the apps can only record consent and add events; they cannot read, change
or delete anything (19 automated checks cover this). Never share the **service_role** key or put it in an app.

## 2. What gets collected

| Category | Examples | Severity |
|---|---|---|
| `crash` | The app closed unexpectedly: exception and stack trace | critical |
| `error` | A text that failed, GPS that didn't answer, an update that didn't install | error / warning |
| `security` | Wrong safety PIN, evidence that fails its integrity check, a download with the wrong fingerprint | warning / critical |
| `false_alarm` | A pocket or watch countdown the tester cancelled, an automatic call cancelled | warning / info |
| `usage` | SOS started (and how: button, knock, watch, voice), Safe Journey, Fake call | info |
| `performance` | App start time, SOS to first text, watch to phone delivery time (`detail.ms`) | info |
| `device` | Phone or watch brand, model, Android version, screen size | info |
| `feedback` | A problem the tester reported in **Me → Beta testing → Report a problem** | as she chose |

**Never collected:** location, phone numbers, contact names, messages, recordings, the tester's name, Aadhaar or
account details. The phone strips numbers, coordinates, links, emails, IP addresses and known names from any text
before it is queued (unit-tested in `ScrubTest`), and messages from location-related parts of the app are reduced
to their technical error type.

Each tester is a random 32-character ID. Events wait on the phone (up to 2,000) until there is internet, then upload
in batches. A tester can change her choices or delete everything she sent from **Me → Beta testing**; on the watch,
turning sharing off deletes it.

## 3. Reading the data

In Supabase, open **Table Editor** or **SQL Editor**:

| View | Answers |
|---|---|
| `beta_problems` | Every distinct crash, error, security event, false alarm and report, with how many times it happened, how many testers it affected, first and last seen, the app versions and the latest message. **Start here.** |
| `beta_daily` | Events per day and category: is the beta getting healthier? |
| `beta_usage` | How often each feature is used, plus average and 90th-percentile timings |
| `beta_devices` | Which phones and watches are in the test |
| `beta_events` | Every raw event, with `detail` (JSON) for context |

Useful queries:

```sql
-- Top 20 problems right now
select * from beta_problems limit 20;

-- All crashes with their stack traces
select happened_at, app, app_version, message from beta_events where category = 'crash' order by happened_at desc;

-- Security events
select * from beta_problems where category = 'security';

-- False alarms by trigger (knock, shake, voice…)
select detail ->> 'trigger' as trigger, count(*) from beta_events where category = 'false_alarm' group by 1 order by 2 desc;

-- Tester reports, newest first
select happened_at, severity, code, message, detail ->> 'recent_errors' as recent_errors
from beta_events where category = 'feedback' order by happened_at desc;
```

Export any result with **Download CSV**.

## 4. Working through problems

1. Each morning of the test, open `beta_problems`. Sort by `testers_affected`, then `occurrences`.
2. Treat `crash`, `security` and anything with `worst_severity = 'critical'` first: these block an SOS or break trust.
3. For each problem, open a GitHub issue titled with the `code`, paste the `fingerprint`, and fix it.
4. Release the fix (`bash web-install/publish.sh "…"`). The new `app_version` shows up in `beta_problems.versions`;
   when a problem stops appearing in the new version, close the issue.
5. Before a commercial launch, aim for zero open `crash` and `security` problems and a falling false-alarm rate.

## 5. When the beta ends

Delete the data, as promised to testers:

```sql
truncate public.beta_events; truncate public.beta_consents;
```

Then remove the `"beta"` entry from `version.json` so the apps stop uploading.
