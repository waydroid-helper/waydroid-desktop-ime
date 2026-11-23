#!/bin/sh
set -eu
src=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
out="$src/.build"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
platform=${ANDROID_JAR:-"$sdk/platforms/android-36/android.jar"}
bt=${ANDROID_BUILD_TOOLS:-"$sdk/build-tools/36.0.0"}
mkdir -p "$out"
work=$(mktemp -d "$out/build-XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir "$work/classes" "$work/dex"
"$bt/aapt2" compile --dir "$src/res" -o "$work/resources.zip"
"$bt/aapt2" link -I "$platform" --manifest "$src/AndroidManifest.xml" -o "$work/unsigned.apk" "$work/resources.zip"
javac --release 8 -classpath "$platform" -d "$work/classes" "$src"/src/org/waydroid/helper/desktopime/*.java
"$bt/d8" --min-api 30 --lib "$platform" --output "$work/dex" "$work/classes"/org/waydroid/helper/desktopime/*.class
(cd "$work/dex" && zip -q -0 ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
if [ "${1:-}" = "--release" ]; then
    : "${IME_KEYSTORE:?Set IME_KEYSTORE to the release PKCS12 file}"
    : "${IME_PASSWORD_FILE:?Set IME_PASSWORD_FILE to its protected password file}"
    key=$IME_KEYSTORE
    pass="file:$IME_PASSWORD_FILE"
    artifact=waydroid-desktop-ime.apk
elif [ "$#" = 0 ]; then
    key="$out/development.p12"
    pass=pass:android
    if [ ! -f "$key" ]; then
        (umask 077; keytool -genkeypair -keystore "$key" -storetype PKCS12 -storepass android \
            -alias desktop-ime -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Local Android Development")
    fi
    artifact=waydroid-desktop-ime-dev.apk
else
    echo "Usage: ./build.sh [--release]" >&2
    exit 2
fi
"$bt/apksigner" sign --ks "$key" --ks-pass "$pass" --v4-signing-enabled false --out "$work/$artifact" "$work/aligned.apk"
"$bt/apksigner" verify "$work/$artifact"
mv "$work/$artifact" "$out/$artifact"
(cd "$out" && sha256sum "$artifact" > "$artifact.sha256")
