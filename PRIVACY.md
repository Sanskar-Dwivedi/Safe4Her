# Privacy

Security4Her is built to share as little as possible, and only when she decides to. There are no ads and no
third-party trackers in either app. During the beta, testers can choose to share anonymous test data (below).

## What leaves the phone, and when

| Data | When | Where it goes |
|---|---|---|
| Her location (a Google Maps link) and name | During an SOS | By SMS to the trusted contacts she chose |
| Her live location | During an SOS, a Safe Journey, or when she taps *Live location* | A private, randomly named topic on [ntfy.sh](https://ntfy.sh), shown on the live-map page linked in the SMS. Sharing stops when she ends it. |
| A call | After the SOS countdown | The number she set (or her test number while Test Mode is on) |
| Approximate map area | When the map looks for nearby hospitals, police and routes | OpenStreetMap services (Nominatim, Overpass, OSRM). Coordinates only, no identity. |
| Places she adds to the safety map, and where SOS alerts happen | When she adds a report, and once per SOS | A shared community topic on ntfy.sh. SOS locations are rounded to about 100 m and carry no name or number. |
| Account, contacts and incidents | Only if the app is connected to a Supabase backend | That backend, protected by row-level security |
| Update checks | Every few hours | `security4her.vercel.app`, an ordinary web request for `version.json` |

## What stays on the device

- **Evidence**: audio and video recorded during an SOS stay on the phone, fingerprinted with SHA-256 and
  locked against deletion for 24 hours. They leave only if she exports them.
- **Voice SOS**: speech is recognised entirely on the watch. Nothing is recorded, stored or sent; the app
  only reacts to "HELP HELP HELP".
- **Knock and shake detection**: motion data is processed on the device and discarded.
- **The in-app assistant** runs offline.
- **Contacts** stay on the phone unless a backend is connected. Phone contacts are read only when she picks one.
- **Verification**: DigiLocker verification keeps only whether she is verified. No Aadhaar number, address,
  photo or document is stored.

## Beta test data

Security4Her 1.1 is a beta. On first launch the app asks whether you want to share anonymous test data, and
which kinds: crash reports, errors, security events, false alarms, feature use, speed and phone model. Nothing is
sent unless you agree, and the app works the same if you don't.

- You are identified only by a random tester ID, never by name, number or account.
- Location, phone numbers, contact names, messages, recordings and Aadhaar are never included. Numbers, places,
  links and names are removed on your phone before anything is queued.
- Data goes over an encrypted connection to a private database that only the Security4Her team can read, and the
  server rejects anything you haven't agreed to.
- It is used only to fix the app during the beta and is deleted when the beta ends.
- Change your choices or delete everything you've sent at any time: **Me → Beta testing** on the phone, or **Beta
  test data** on the watch.

## Permissions

Location (SOS, journeys, map), SMS and phone (sending the SOS and the call), microphone and camera (evidence;
Voice SOS on the watch), contacts (picking trusted contacts), notifications, and installing updates. Each is
asked for when the feature first needs it, and the app keeps working without the optional ones.

## Deleting data

Uninstalling removes everything the apps keep on the phone and watch. Messages on ntfy.sh expire on their own
within a day. If a Supabase backend is connected, signing out and asking the operator removes the account.
