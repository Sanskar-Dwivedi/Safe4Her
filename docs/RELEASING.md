# Releasing a new version

Both apps update themselves, so a release is: bump the version, run one script.

## 1. Bump the version

| App | File | Fields |
|---|---|---|
| Phone | `mobile/build.gradle.kts` | `versionCode` (+1), `versionName` |
| Watch | `wear/build.gradle.kts` | `versionCode` (+1), `versionName` |

Only apps whose `versionCode` goes up are offered to users, so you can release one without the other. Run
the [device test checklist](TESTING.md) on the builds first.

## 2. Publish

```bash
bash web-install/publish.sh "What's new, in one sentence"
```

The script:
1. builds both apps in release mode (`:mobile:assembleRelease :wear:assembleRelease`, shrunk with R8);
2. copies the APKs into `web-install/`;
3. writes `web-install/version.json` with each app's version, SHA-256 and size, plus your release note;
4. updates the sizes, versions and fingerprint shown on the install page;
5. deploys `web-install/` to Vercel (run `vercel login` once on the machine first).

## 3. What users see

- **Phone:** on next launch (checked at most every 6 hours, or via Me → App updates → Check), Home shows
  *Update to X* with the release note. The app downloads the APK, checks its SHA-256 and opens Android's
  installer. The first time, Android asks to allow installs from Security4Her.
- **Watch:** nothing to do. A background job checks every 6 hours, downloads the APK, checks its SHA-256
  and installs it silently. It never updates during an SOS, and while Voice SOS or Safety Mode is on it
  waits until the watch is charging. After the update, protection comes back on its own or with one tap on
  a *Tap to resume* notification.

Data, accounts and the phone–watch link survive updates because the package name and signing key stay the
same.

## `version.json`

```json
{
  "versionCode": 10, "versionName": "1.0.0", "apk": "Security4Her-phone.apk",
  "sha256": "…", "bytes": 10924143, "notes": "…",
  "watch": { "versionCode": 8, "versionName": "1.0.0", "apk": "Security4Her-watch.apk", "sha256": "…", "bytes": 53129076 }
}
```

The update URL defaults to `https://security4her.vercel.app/version.json`; override it with `UPDATE_URL=`
in `local.properties`. For testing watch updates against a local server, `UPDATE_URL=http://localhost:8000/version.json`
plus `adb reverse tcp:8000 tcp:8000` works (plain HTTP is allowed only for `localhost`).

## Signing

Every build, debug and release, is signed with the key in `keystore/`. That keeps every installed copy
updatable, and Wear OS needs the phone and watch apps signed with the same key to talk to each other.

Before the repository or the key is shared more widely, move to a private release key. Android supports
rotating to a new key without a reinstall (APK Signature Scheme v3 key rotation with `apksigner rotate`),
as long as the old key signs the rotation once.
