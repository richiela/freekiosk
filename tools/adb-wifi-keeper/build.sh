#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
SDK=/opt/homebrew/share/android-commandlinetools
JAR=$SDK/platforms/android-36/android.jar
BT=$SDK/build-tools/36.0.0
KS=$HOME/.android/debug.keystore
if [ ! -f "$KS" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
rm -rf build; mkdir -p build/classes
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 30 --target-sdk-version 36 build/res.zip -o build/base.apk
javac --release 11 -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q -j base.apk classes.dex)
"$BT/zipalign" -p -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out build/kiosk-adb-keeper.apk build/aligned.apk
"$BT/apksigner" verify --print-certs build/kiosk-adb-keeper.apk
echo "built build/kiosk-adb-keeper.apk"
