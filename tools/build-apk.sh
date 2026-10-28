#!/usr/bin/env bash
# Builds a signed release APK without the Android SDK / Gradle, using:
#   aapt (v1), dx (dalvik-exchange), zipalign, apksigner  - e.g. Debian/Ubuntu packages
#     sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
#   javac (JDK 11+)
#   A framework jar that contains resources.arsc, e.g. Robolectric's
#     org.robolectric:android-all:13-robolectric-9030017 (API 33, Maven Central).
#     (aapt v1 cannot read the API 34+ resource table format, so use API 33 here; the
#     manifest still declares targetSdkVersion 35.)
#
# Android Studio users do not need this script: open the project and build normally.
#
# Environment:
#   FRAMEWORK_JAR   framework jar with resources.arsc (API 33)  (required)
#   JAVA_STUBS_JAR  android.jar providing java.* (API 23)       (default: Ubuntu path)
#   KEYSTORE / KS_PASS / KEY_ALIAS   signing key; a throw-away key is generated if unset
#   OUT             output APK path (default: build/zero-launcher.apk)
#   PROGUARD_LIB    optional: directory with proguard-base 7.x and its dependencies; when set the
#                   classes are shrunk/optimized like the Gradle release build does with R8
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
B="$ROOT/build/manual"
OUT="${OUT:-$ROOT/build/zero-launcher.apk}"
FRAMEWORK_JAR="${FRAMEWORK_JAR:?set FRAMEWORK_JAR to a framework jar (see header)}"
JAVA_STUBS_JAR="${JAVA_STUBS_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"

PKG=io.github.plpp555.zerolauncher
MIN_SDK=21
TARGET_SDK=35
VERSION_CODE=1
VERSION_NAME=1.0

DX="$(command -v dx || command -v dalvik-exchange)"

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/manifest"

# Gradle supplies the package name from "namespace"; aapt v1 needs it in the manifest.
sed "s#<manifest #<manifest package=\"$PKG\" #" "$APP/AndroidManifest.xml" > "$B/manifest/AndroidManifest.xml"

echo "== aapt"
aapt package -f -m \
    -M "$B/manifest/AndroidManifest.xml" \
    -S "$APP/res" \
    -I "$FRAMEWORK_JAR" \
    -J "$B/gen" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE --version-name $VERSION_NAME \
    -0 arsc \
    -F "$B/res.ap_"

echo "== javac"
# java.* from the API 23 stubs (close to minSdk), android.* from the framework jar.
find "$APP/java" "$B/gen" -name '*.java' > "$B/sources.txt"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$FRAMEWORK_JAR:$JAVA_STUBS_JAR" \
    -d "$B/classes" @"$B/sources.txt"

DEX_INPUT="$B/classes"
if [ -n "${PROGUARD_LIB:-}" ]; then
    echo "== proguard (shrink + optimize + obfuscate)"
    PG_CP="$(ls "$PROGUARD_LIB"/*.jar | tr '\n' ':')"
    java -cp "$PG_CP" proguard.ProGuard @"$ROOT/tools/proguard-local.pro" \
        -injars "$B/classes" -outjars "$B/classes-pg.jar" \
        -libraryjars "$FRAMEWORK_JAR" -libraryjars "$JAVA_STUBS_JAR" \
        -dontpreverify -printmapping "$B/mapping.txt" > "$B/proguard.log"
    # Same optimizations without renaming, keeping what the tests reach by reflection, so
    # tools/run-tests.sh can run the suite against optimized code (APP_CLASSES=...).
    # Preverified, because the JVM (unlike Android) needs stack map frames.
    java -cp "$PG_CP" proguard.ProGuard @"$ROOT/tools/proguard-local.pro" \
        -injars "$B/classes" -outjars "$B/classes-pg-test.jar" \
        -libraryjars "$FRAMEWORK_JAR" -libraryjars "$JAVA_STUBS_JAR" \
        -dontobfuscate @"$ROOT/tools/offline-test/keep-for-tests.pro" > "$B/proguard-test.log"
    DEX_INPUT="$B/classes-pg.jar"
fi

echo "== dx"
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$B/classes.dex" "$DEX_INPUT"

echo "== package"
cp "$B/res.ap_" "$B/unsigned.apk"
(cd "$B" && zip -q -X -9 unsigned.apk classes.dex)
zipalign -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "== sign"
if [ -z "${KEYSTORE:-}" ]; then
    KEYSTORE="$ROOT/build/throwaway.jks"
    KS_PASS=throwaway
    KEY_ALIAS=zero
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
            -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Zero Launcher local build" >/dev/null 2>&1
    fi
fi
mkdir -p "$(dirname "$OUT")"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version $MIN_SDK --out "$OUT" "$B/aligned.apk"
apksigner verify --min-sdk-version $MIN_SDK "$OUT"
rm -f "$OUT.idsig"

echo "== done: $OUT ($(stat -c %s "$OUT") bytes)"
