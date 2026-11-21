#!/usr/bin/env bash
# Builds GroundScreen without Gradle, using Debian/Ubuntu packaged Android tools:
#   apt-get install android-sdk-platform-23 android-sdk-build-tools dalvik-exchange openjdk-17-jdk
# Java compile classpath: Robolectric's android-all (Android 9 framework classes) from Maven Central;
# java.* comes from javac --release 8 (core Java 8 API signatures).
# Output: build/GroundScreen.apk (signed with a local debug key).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
TOOLS="${TOOLS_DIR:-$ROOT/.tools}"
SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
BT="$SDK/build-tools/debian"
[ -d "$BT" ] || BT="$(ls -d "$SDK"/build-tools/* | tail -1)"
PLATFORM_JAR="$SDK/platforms/android-23/android.jar"
MAVEN="https://repo.maven.apache.org/maven2"
# Keep in sync with app/build.gradle
PKG="com.groundscreen.tv"
VERSION_CODE="$(sed -n "s/.*versionCode \([0-9]*\).*/\1/p" "$ROOT/app/build.gradle")"
VERSION_NAME="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" "$ROOT/app/build.gradle")"

ANDROID_ALL="org/robolectric/android-all/9-robolectric-4913185-2/android-all-9-robolectric-4913185-2.jar"
LIBS=(
  "net/i2p/crypto/eddsa/0.3.0/eddsa-0.3.0.jar"
)

mkdir -p "$TOOLS" "$OUT"
fetch() {
  local f="$TOOLS/$(basename "$1")"
  if [ ! -s "$f" ]; then
    echo "Downloading $(basename "$1")"
    for i in 1 2 3 4; do curl -sfL -o "$f" "$MAVEN/$1" && break; sleep $((i * 3)); done
    [ -s "$f" ] || { echo "download failed: $1"; exit 1; }
  fi
  echo "$f"
}
BOOT="$(fetch "$ANDROID_ALL")"
CP=""
for l in "${LIBS[@]}"; do CP="$CP:$(fetch "$l")"; done
CP="${CP#:}"

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/res.zip" "$OUT/apk"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk"

echo "== aapt2 compile/link"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
# The source manifest has no package attribute (AGP 8 style); this aapt2 needs one.
sed "s|<manifest |<manifest package=\"$PKG\" |" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay "$OUT/res.zip"

echo "== javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 \
  -classpath "$BOOT:$CP" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex"
dalvik-exchange --dex --min-sdk-version=21 --output="$OUT/apk/classes.dex" "$OUT/classes" ${CP//:/ }

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/apk" && zip -q -X "$OUT/unsigned.apk" classes.dex)
# Java resources (FairPlay tables) at the APK root, loaded with Class.getResource().
(cd "$APP/resources" && zip -q -r -X "$OUT/unsigned.apk" .)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KEYSTORE="${KEYSTORE:-$ROOT/tools/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GroundScreen Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/GroundScreen.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/GroundScreen.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/base.apk"
echo "OK -> $OUT/GroundScreen.apk ($(du -h "$OUT/GroundScreen.apk" | cut -f1))"
