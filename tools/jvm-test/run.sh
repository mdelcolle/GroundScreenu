#!/usr/bin/env bash
# Runs the protocol harness on the desktop JVM (no Android device needed).
# curve25519-java is only used here, as an independent sender-side X25519.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP="$ROOT/app/src/main"
T="$ROOT/.tools"
OUT="$ROOT/build/jvm-test"
MAVEN="https://repo.maven.apache.org/maven2"
mkdir -p "$T" "$OUT"
for a in net/i2p/crypto/eddsa/0.3.0/eddsa-0.3.0.jar \
         org/whispersystems/curve25519-java/0.5.0/curve25519-java-0.5.0.jar \
         com/googlecode/plist/dd-plist/1.30/dd-plist-1.30.jar; do
  f="$T/$(basename $a)"
  [ -s "$f" ] || curl -sfL -o "$f" "$MAVEN/$a"
done
CP="$T/eddsa-0.3.0.jar:$T/curve25519-java-0.5.0.jar:$T/dd-plist-1.30.jar"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
{
  find "$APP/java/com/github" -name '*.java'
  find "$APP/java/com/groundscreen/tv/util" "$APP/java/com/groundscreen/tv/crypto" -name '*.java'
  echo "$APP/java/com/groundscreen/tv/media/H264Util.java"
  echo "$APP/java/com/groundscreen/tv/media/AlacDecoder.java"
  for f in MirrorListener ReceiverConfig AirPlayFeatures RtspConnection MirrorVideoReceiver AudioReceiver; do
    echo "$APP/java/com/groundscreen/tv/server/$f.java"
  done
  find "$ROOT/tools/jvm-test" -name '*.java'
} > "$OUT/sources.txt"
javac -nowarn --release 8 -encoding UTF-8 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"
cp -r "$APP/resources/." "$OUT/classes/"
java -cp "$OUT/classes:$CP" com.groundscreen.tv.server.ProtocolHarness
