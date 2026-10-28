#!/usr/bin/env bash
# Runs the Robolectric tests (app/src/test) without Gradle, against the output of build-apk.sh.
# Android Studio users: run the tests with `./gradlew testDebugUnitTest` instead.
#
# Environment:
#   FRAMEWORK_JAR  framework jar used for compiling (same as build-apk.sh)          (required)
#   ROBO_LIB       directory with robolectric 4.17 + junit 4.13.2 and their deps    (required)
#   ROBO_SDK_DIR   directory with android-all-instrumented-*-i7.jar files          (required)
#   APP_CLASSES    app classes to test (default: build/manual/classes; use
#                  build/manual/classes-pg-test.jar to test the ProGuard-optimized code)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B="$ROOT/build/manual"
T="$ROOT/build/test"
: "${FRAMEWORK_JAR:?}" "${ROBO_LIB:?}" "${ROBO_SDK_DIR:?}"

[ -d "$B/classes" ] || { echo "run tools/build-apk.sh first" >&2; exit 1; }
APP_CLASSES="${APP_CLASSES:-$B/classes}"
rm -rf "$T"
mkdir -p "$T/classes" "$T/res/com/android/tools" "$T/assets"

# Merged manifest as AGP would produce it (package + uses-sdk).
sed 's#<queries>#<uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />\n    <queries>#' \
    "$B/manifest/AndroidManifest.xml" > "$T/AndroidManifest.xml"
cat > "$T/res/com/android/tools/test_config.properties" <<PROPS
android_merged_manifest=$T/AndroidManifest.xml
android_merged_assets=$T/assets
android_resource_apk=$B/res.ap_
android_custom_package=io.github.plpp555.zerolauncher
PROPS

CP="$(ls "$ROBO_LIB"/*.jar | tr '\n' ':')"

# androidx.test is only published on Google's Maven; use the local stand-ins (see their README).
mkdir -p "$T/stubs"
find "$ROOT/tools/offline-test/androidx-stubs" -name '*.java' > "$T/stubs.txt"
javac -nowarn -encoding UTF-8 --release 17 -d "$T/stubs" -cp "$FRAMEWORK_JAR" @"$T/stubs.txt"
CP="$CP$T/stubs:"
find "$ROOT/app/src/test/java" -name '*.java' > "$T/sources.txt"
javac -nowarn -encoding UTF-8 --release 17 -d "$T/classes" \
    -cp "$CP$B/classes:$FRAMEWORK_JAR" @"$T/sources.txt"

java -Xmx3g \
    -Drobolectric.offline=true -Drobolectric.dependency.dir="$ROBO_SDK_DIR" \
    -cp "$T/classes:$T/res:$APP_CLASSES:$CP$FRAMEWORK_JAR" \
    org.junit.runner.JUnitCore io.github.plpp555.zerolauncher.LauncherTest
