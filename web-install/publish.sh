#!/usr/bin/env bash
# Publish a release of the phone and watch apps: build both, copy the APKs here, write version.json, refresh the
# page's sizes and fingerprint, deploy. Phone 0.2.0+ and watch 0.3.0+ find the update on their own.
#
#   1. Bump versionCode and versionName in mobile/build.gradle.kts and/or wear/build.gradle.kts
#   2. bash web-install/publish.sh "What's new, in one sentence"
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:-Fixes and improvements.}"

export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/s4h-tools/jdk17}"
./gradlew :mobile:assembleRelease :wear:assembleRelease --console=plain -q

ver() { grep -oE "$2 = \"?[^\"]+\"?" "$1/build.gradle.kts" | head -1 | sed -E "s/$2 = \"?([^\"]+)\"?/\1/"; }
P_CODE=$(ver mobile versionCode); P_NAME=$(ver mobile versionName)
W_CODE=$(ver wear versionCode);   W_NAME=$(ver wear versionName)
cp mobile/build/outputs/apk/release/mobile-release.apk web-install/Security4Her-phone.apk
cp wear/build/outputs/apk/release/wear-release.apk web-install/Security4Her-watch.apk
cd web-install

python - "$P_CODE" "$P_NAME" "$W_CODE" "$W_NAME" "$NOTES" <<'PY'
import hashlib, json, os, re, sys
p_code, p_name, w_code, w_name, notes = sys.argv[1:]
def info(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest(), os.path.getsize(path)
p_sha, p_size = info("Security4Her-phone.apk")
w_sha, w_size = info("Security4Her-watch.apk")
json.dump({
    "versionCode": int(p_code), "versionName": p_name, "apk": "Security4Her-phone.apk",
    "sha256": p_sha, "bytes": p_size, "notes": notes,
    "watch": {"versionCode": int(w_code), "versionName": w_name, "apk": "Security4Her-watch.apk", "sha256": w_sha, "bytes": w_size},
}, open("version.json", "w"), indent=2)

s = open("index.html", encoding="utf-8").read()
mb = lambda b: "%.1f MB" % (b / 1e6)
s = re.sub(r'(id="sha">)[0-9a-f]+', r'\g<1>' + p_sha, s)
s = re.sub(r'bytes: \d+', 'bytes: %d' % p_size, s)
s = re.sub(r'Phone app [0-9][^ ]* for Android', 'Phone app ' + p_name + ' for Android', s)
s = re.sub(r'Watch app [0-9][^ ]* for Wear OS', 'Watch app ' + w_name + ' for Wear OS', s)
for pat in (r'(id="dl-meta">)[\d.]+ MB', r'(id="ph-meta">)[\d.]+ MB', r'(Download complete, )[\d.]+ MB'):
    s = re.sub(pat, r'\g<1>' + mb(p_size), s)
s = re.sub(r'(Download the watch app <span class="meta">)[\d.]+ MB', r'\g<1>' + mb(w_size), s)
open("index.html", "w", encoding="utf-8").write(s)
print("phone %s (%s)  watch %s (%s)" % (p_name, mb(p_size), w_name, mb(w_size)))
PY

cp Security4Her-phone.apk "$USERPROFILE/Desktop/Security4Her-phone.apk" 2>/dev/null || true
cp Security4Her-watch.apk "$USERPROFILE/Desktop/Security4Her-watch.apk" 2>/dev/null || true
vercel deploy --prod --yes >/dev/null
# Tell Bing and other IndexNow search engines the page changed.
KEY=$(ls | grep -E '^[0-9a-f]{32}\.txt$' | head -1 | sed 's/\.txt$//')
[ -n "$KEY" ] && curl -s -o /dev/null -X POST https://api.indexnow.org/IndexNow -H "Content-Type: application/json; charset=utf-8"   -d "{\"host\":\"security4her.vercel.app\",\"key\":\"$KEY\",\"urlList\":[\"https://security4her.vercel.app/\"]}" || true
echo "Published phone $P_NAME ($P_CODE) and watch $W_NAME ($W_CODE)"
