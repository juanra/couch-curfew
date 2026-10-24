#!/usr/bin/env bash
# Builds CouchCurfew.apk without Gradle, the way Free My TV does: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK command-line tools (build-tools 34+, platform android-34). See README.md.
set -euo pipefail
cd "$(dirname "$0")"

# --- Tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

# --- Build
rm -rf out && mkdir -p out/gen out/classes
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 26 --target-sdk-version 33
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# --- Sign with your own key (created once; updates need the same key, so keep couchcurfew.keystore)
if [ ! -f couchcurfew.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore couchcurfew.keystore -alias couchcurfew -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass couchcurfew -keypass couchcurfew -dname "CN=Couch Curfew" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks couchcurfew.keystore --ks-pass pass:couchcurfew --key-pass pass:couchcurfew \
  --out CouchCurfew.apk out/aligned.apk
echo "Built CouchCurfew.apk ($(du -h CouchCurfew.apk | cut -f1)). Install: adb install -r CouchCurfew.apk"
