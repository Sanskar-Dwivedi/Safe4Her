# Releasing a new version

Phone and watch apps update themselves from inside the app. A release is: bump the version, run one
script.

## 1. Bump the version

| App | File | Fields |
|---|---|---|
| Phone | `mobile/build.gradle.kts` | `versionCode` (+1), `versionName` |
| Watch | `wear/build.gradle.kts` | `versionCode` (+1), `versionName` |

Only apps whose `versionCode` goes up are offered to users; you can release one without the other.

## 2. Publish

```bash
bash web-install/publish.sh "What's new, in one sentence"
```

The script:
1. builds both apps (`:mobile:assembleDebug :wear:assembleDebug`);
2. copies the APKs into `web-install/`;
3. writes `web-install/version.json` with each app's version, SHA-256 and size, plus your release note;
4. updates the sizes, versions and fingerprint shown on the install page;
5. deploys `web-install/` to Vercel (`vercel login` once on the machine first).

## 3. What users see

- **Phone:** on next launch (checked at most every 6 hours, or via Me → App updates → Check), Home shows
  *Update to X* with the release note. The app downloads the APK, verifies its SHA-256 and opens
  Android's installer. The first time, Android asks to allow installs from Security4Her.
- **Watch:** the home screen shows *Update to X*; same download, check and install on the watch.

Data, accounts and the phone–watch link survive updates because the package name and signing key stay
the same.

## `version.json`

```json
{
  "versionCode": 7, "versionName": "0.3.3", "apk": "Security4Her-phone.apk",
  "sha256": "…", "bytes": 19911101, "notes": "…",
  "watch": { "versionCode": 5, "versionName": "0.3.1", "apk": "Security4Her-watch.apk", "sha256": "…", "bytes": 33865488 }
}
```

The update URL defaults to `https://security4her.vercel.app/version.json`; override it with
`UPDATE_URL=` in `local.properties`.

## Signing

Builds are signed with the shared debug keystore in `keystore/` so every teammate's build can update
every install. Before a public or Play Store release, create a release keystore (never commit it;
`keystore/release*` is ignored) and note that users will need to reinstall once when the key changes.
