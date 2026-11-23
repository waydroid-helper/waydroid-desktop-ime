# Waydroid Desktop IME

Type in Android apps using your Linux desktop input method. The embedded Waydroid Helper window forwards composition and candidate selection to the focused Android editor. Android keeps ownership of the text, selection and cursor.

In the embedded emulator, open **More → Set up desktop input method…** and confirm **Install and enable**. Click an Android text field to type. Key mappings pause during text entry and resume when input ends. This requires a Waydroid Helper build with desktop IME support and Android 11 or later.

## Android bridge

The APK exposes an Android input method and a shell-only content provider. The desktop connects through the current Android user's ADB transport. Each editor has a separate session identifier, so delayed commands for the previous field are discarded. The guest sends cursor geometry where the app provides it. The APK requests no network permission.

## Build

Install a JDK, zip, Android SDK platform 36 and build tools 36.0.0. Set `ANDROID_HOME` if your SDK is outside `~/Android/Sdk`. Run `./build.sh` to build a development APK under `.build/`.

For a release, update the Android version code and version name, then run `./build.sh --release` with `IME_KEYSTORE` and `IME_PASSWORD_FILE` pointing to the release PKCS12 keystore and its password file. Publish `waydroid-desktop-ime.apk` and its SHA-256 checksum from the tagged source. The desktop client pins the release and SHA-256 digest.

Licensed under GPL-3.0-or-later.
