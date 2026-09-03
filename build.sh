#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT="$HOME/github/termux-widget-apk"
OUT="$PROJECT/out"
SRC="$PROJECT/src"
PKG="com.runner"
PKG_DIR="$(echo "$PKG" | tr '.' '/')"
DEX="$OUT/classes.dex"
APK_SIGNED="$OUT/termux-tasks.apk"
KEYSTORE="$OUT/keystore.jks"
KEY_ALIAS="termuxwidget"
STORE_PASS="termuxtasks"
KEY_PASS="termuxtasks"
FRAMEWORK="/system/framework/framework-res.apk"

VC=$(($(cat "$PROJECT/.version" 2>/dev/null || echo 0) + 1))
echo "$VC" > "$PROJECT/.version"
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" "$PROJECT/AndroidManifest.xml"
echo "Build versionCode=$VC"

rm -rf "$OUT"
mkdir -p "$OUT" "$OUT/classes" "$OUT/dexed"

echo "=== 1. Compile resources with aapt (old) ==="
aapt package -f \
  -M "$PROJECT/AndroidManifest.xml" \
  -S "$PROJECT/res" \
  -I "$FRAMEWORK" \
  -J "$SRC" \
  --no-version-vectors 2>/dev/null
mkdir -p "$SRC/$PKG_DIR"
mv "$SRC/R.java" "$SRC/$PKG_DIR/R.java" 2>/dev/null || true

echo "=== 2. Compile Java with javac ==="
javac \
  -d "$OUT/classes" \
  -source 1.8 -target 1.8 \
  -bootclasspath "$PREFIX/share/java/android.jar" \
  $(find "$SRC/$PKG_DIR" -name '*.java')

echo "=== 3. Convert to DEX ==="
dx --dex --output="$DEX" "$OUT/classes"

echo "=== 4. Build APK with aapt (old) ==="
aapt package -f \
  -M "$PROJECT/AndroidManifest.xml" \
  -S "$PROJECT/res" \
  -I "$FRAMEWORK" \
  -F "$OUT/unsigned.apk" \
  --no-version-vectors 2>/dev/null

echo "=== 5. Add DEX ==="
zip -0 "$OUT/unsigned.apk" -j "$DEX"

echo "=== 6. Sign ==="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkey -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=TermuxTasks, O=TermuxTasks, C=US" 2>/dev/null
fi

apksigner sign \
  --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$APK_SIGNED" "$OUT/unsigned.apk"

rm -f "$OUT/unsigned.apk"

echo ""
echo "=== DONE ==="
echo "APK: $APK_SIGNED ($(du -h "$APK_SIGNED" | cut -f1))"
echo ""
echo "  cp $APK_SIGNED /storage/emulated/0/Download/"
echo "  tap file in Files app"