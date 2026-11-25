#!/usr/bin/env bash
# Construit Boucle.apk sans Gradle : aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage : bash build.sh   (depuis Git Bash)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2.exe" compile --dir app/res -o build/res.zip
"$BT/aapt2.exe" link -o build/base.apk -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml -A app/assets --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

javac -nowarn -source 11 -target 11 -encoding UTF-8 -classpath "$PLATFORM" -d build/classes \
  $(find build/gen app/src -name '*.java')
"$BT/d8.bat" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')

python - <<'PY'
import zipfile
with zipfile.ZipFile('build/base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY

"$BT/zipalign.exe" -p -f 4 build/base.apk build/aligned.apk

KS=keystore/boucle.jks
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KS" -alias boucle -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass boucle-local -keypass boucle-local -dname "CN=Boucle"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:boucle-local --key-pass pass:boucle-local \
  --out Boucle.apk build/aligned.apk
"$BT/apksigner.bat" verify Boucle.apk && echo "OK : $(pwd)/Boucle.apk"
