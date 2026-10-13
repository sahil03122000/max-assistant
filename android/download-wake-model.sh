#!/usr/bin/env bash
# "Hello Max" ka offline model (Vosk, Indian English, ~36 MB) download karke app ke assets mein daalta hai.
# Git mein nahi rakha kyunki bada hai. Naye computer par build karne se pehle ek baar chalao:
#   bash android/download-wake-model.sh
set -euo pipefail

MODEL=vosk-model-small-en-in-0.4
DEST="$(cd "$(dirname "$0")" && pwd)/app/src/main/assets/vosk-model"
TMP="$(mktemp -d)"

curl -L --fail -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/$MODEL.zip"
unzip -q "$TMP/model.zip" -d "$TMP"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$TMP/$MODEL"/{am,conf,graph,ivector} "$DEST"/
echo "en-in-0.4" > "$DEST/uuid"
rm -rf "$TMP"
echo "✅ Wake word model ready: $DEST"
