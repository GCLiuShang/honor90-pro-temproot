#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/projects/android-sdk}"
export PATH="$SDK/build-tools/android-15:$PATH"
AJ="$SDK/platforms/android-35/android.jar"
CP="$AJ:libs/shizuku-api.jar:libs/shizuku-provider.jar:libs/shizuku-aidl.jar:libs/xposed-api-stub.jar"
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt2 compile --dir app/src/main/res -o build/res.zip
# Plain aapt2 does NOT substitute ${applicationId}. A leftover placeholder
# silently breaks the Shizuku provider authority, so the server can never
# deliver the binder and pingBinder() stays false forever.
if grep -q '\${' app/src/main/AndroidManifest.xml; then
  echo "FATAL: unresolved \${...} placeholder in AndroidManifest.xml" >&2; exit 1
fi
aapt2 link -o build/unsigned.apk -I $AJ --manifest app/src/main/AndroidManifest.xml \
  --java build/gen -A app/src/main/assets --min-sdk-version 26 --target-sdk-version 35 \
  --auto-add-overlay build/res.zip
javac -source 17 -target 17 -nowarn -cp "$CP" -d build/classes \
  build/gen/com/ghostlock/ksuapp/R.java app/src/main/java/com/ghostlock/ksuapp/*.java \
  app/src/main/java/rikka/shizuku/*.java
d8 --lib $AJ --lib libs/xposed-api-stub.jar --min-api 26 --output build/dex $(find build/classes -name '*.class') \
  libs/shizuku-api.jar libs/shizuku-provider.jar libs/shizuku-aidl.jar
cp build/unsigned.apk build/app.apk
(cd build && zip -q -j app.apk dex/classes.dex)
zipalign -f 4 build/app.apk build/app-aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
  -alias dbg -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
  -dname "CN=GhostLock" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/app-signed.apk build/app-aligned.apk
echo "BUILT: $(ls -lh build/app-signed.apk | awk '{print $5}')"
