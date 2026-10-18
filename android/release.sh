#!/usr/bin/env bash
# Nayi phone app GitHub Release mein daalo — phir phone par Max → Settings → "App update" se install.
#   bash android/release.sh "kya badla (ek line)"
# Pehle sab commit karo: version = git commits ki ginti (build.gradle.kts).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="sahil03122000/max-assistant"
NOTE="${1:-Naya version}"
cd "$ROOT"
# Android Studio ki Java (alag se Java install na ho to)
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
[ -z "$(git status --porcelain -- app/src)" ] || { echo "❌ Pehle app ke badlaav commit karo"; exit 1; }
CODE=$(git rev-list --count HEAD)
./gradlew -q assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

# GitHub login wahi jo git push mein hai (Windows Credential Manager) — token kahin likha nahi jaata.
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
[ -n "$TOKEN" ] || { echo "❌ GitHub login nahi mila (ek baar git push karo)"; exit 1; }
api() { curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

# Pehle "draft" (chhupi) — upload poora hone tak phone ko adhoori release na dikhe; phir publish.
# Poora JSON node banaye, "—" jaise akshar \u escape karke (Windows shell mein bigad ke GitHub "Problems parsing JSON" deta tha).
BODY=$(CODE="$CODE" SHA="$(git rev-parse HEAD)" NOTE="$NOTE" node -e '
  const j = JSON.stringify({ tag_name: "v" + process.env.CODE, target_commitish: process.env.SHA, name: "Max v" + process.env.CODE, draft: true, body: process.env.NOTE });
  process.stdout.write(j.replace(/[\u007f-￿]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0")));')
RELEASE=$(api -X POST "https://api.github.com/repos/$REPO/releases" -d "$BODY")
ID=$(printf '%s' "$RELEASE" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);if(!j.id){console.error(j.message||s);process.exit(1)}console.log(j.id)})')
echo "⬆️ Release v$CODE bani, APK upload ho rahi hai ($(du -m "$APK" | cut -f1) MB)..."
api -X POST -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=max-v$CODE.apk" -o /dev/null -w "upload: %{http_code}\n"
api -X PATCH "https://api.github.com/repos/$REPO/releases/$ID" -d '{"draft":false}' -o /dev/null -w "publish: %{http_code}\n"
echo "✅ Max v$CODE ready — phone par Settings → App update → Check karo"
