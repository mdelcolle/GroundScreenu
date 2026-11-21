#!/usr/bin/env bash
# Bit-exact check of the Java ALAC decoder against ffmpeg (needs ffmpeg + python3).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/alac-test"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
n=0
for spec in "0:2:sine=frequency=440:sample_rate=44100" \
            "1:2:anoisesrc=color=pink:sample_rate=44100:amplitude=0.6" \
            "2:2:anoisesrc=color=white:sample_rate=44100:amplitude=1.0" \
            "2:2:aevalsrc=0.8*sin(2*PI*220*t)*sin(2*PI*0.5*t)|0.3*sin(2*PI*660*t):sample_rate=44100" \
            "1:1:sine=frequency=1000:sample_rate=44100"; do
  IFS=: read -r level ch src <<<"$spec"
  n=$((n + 1)); name="alac_${n}_l${level}_c${ch}"
  ffmpeg -loglevel error -y -f lavfi -i "$src" -t 3 -ac "$ch" -sample_fmt s16p -c:a alac \
    -compression_level "$level" "$OUT/$name.caf"
  ffmpeg -loglevel error -y -i "$OUT/$name.caf" -f s16le -acodec pcm_s16le "$OUT/$name.raw"
  python3 "$ROOT/tools/jvm-test/caf_to_lp.py" "$OUT/$name.caf" "$OUT/$name.cookie" "$OUT/$name.lp"
done
javac -nowarn --release 8 -d "$OUT/classes" "$ROOT/app/src/main/java/com/groundscreen/tv/media/AlacDecoder.java" \
  "$ROOT/tools/jvm-test/src/com/groundscreen/tv/media/AlacHarness.java"
rc=0
for c in "$OUT"/*.cookie; do
  b="${c%.cookie}"
  java -cp "$OUT/classes" com.groundscreen.tv.media.AlacHarness "$c" "$b.lp" "$b.raw" || rc=1
done
exit $rc
