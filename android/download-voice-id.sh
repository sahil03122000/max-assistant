#!/usr/bin/env bash
# Voice ID ("sirf meri aawaz") ke liye: sherpa-onnx library (~50 MB) + aawaz pehchanne ka model (CAM++, ~30 MB).
# Dono bade hain, git mein nahi — naye computer par build se pehle ek baar:  bash android/download-voice-id.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/app/libs" "$ROOT/app/src/main/assets/voice-id"
curl -L --fail -o "$ROOT/app/libs/sherpa-onnx.aar" \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
curl -L --fail -o "$ROOT/app/src/main/assets/voice-id/speaker.onnx" \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx
echo "✅ Voice ID files ready"
