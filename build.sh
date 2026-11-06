#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
BT="$SDK/build-tools/34.0.0"
D8="$SDK/build-tools/37.0.0/d8"
JAR="$SDK/platforms/android-34/android.jar"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/flat"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/flat.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java "$OUT/gen" -o "$OUT/base.apk" --auto-add-overlay "$OUT/flat.zip"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.list"
javac --release 17 -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.list"
"$D8" --min-api 26 --output "$OUT" "$OUT/classes"/com/xunflash/bandlock/*.class
mkdir -p "$OUT/apk"
unzip -q -o "$OUT/base.apk" -d "$OUT/apk"
cp "$OUT/classes.dex" "$OUT/apk/classes.dex"
mkdir -p "$OUT/apk/assets"
cp "$SRC/assets/qcom-band-menu" "$OUT/apk/assets/qcom-band-menu"
chmod 755 "$OUT/apk/assets/qcom-band-menu"
python3 - "$OUT/apk" "$OUT/unaligned.apk" << 'PY'
import os, sys, zipfile
root, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dest, "w") as z:
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            kind = zipfile.ZIP_STORED if rel == "resources.arsc" else zipfile.ZIP_DEFLATED
            z.write(full, rel, compress_type=kind)
PY
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
    -alias bandlock -keyalg RSA -keysize 2048 -validity 10000 -dname CN=bandlock
fi
"$BT/apksigner" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/bandlock.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$ROOT/bandlock.apk"
echo "built $ROOT/bandlock.apk"
